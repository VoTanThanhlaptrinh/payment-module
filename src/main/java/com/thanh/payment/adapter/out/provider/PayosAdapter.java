package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.PayosProperties;
import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.application.port.out.ProviderCheckoutStatus;
import com.thanh.payment.application.port.out.ProviderOrderCodeGenerator;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;

/**
 * Adapter đi RA phía payOS: gọi POST /v2/payment-requests để tạo link thanh toán (hosted checkout + mã QR chuyển khoản).
 *
 * Khác VNPay: không tự dựng URL mà phải GỌI MẠNG tới payOS. Cuộc gọi có timeout ngắn (mặc định connect 2s + read 5s) vì nó chạy trong
 * DB transaction tạo payment. Lỗi từ payOS đổi thành ApiException (502/503) với thông điệp đã làm sạch, không lộ khoá hay body gốc.
 * Module vẫn KHÔNG nhận dữ liệu thẻ/ngân hàng của khách: khách thanh toán trên trang của payOS.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PayosProperties.class)
public class PayosAdapter implements PaymentProvider {

    // Giới hạn orderCode của payOS = Number.MAX_SAFE_INTEGER của JavaScript.
    private static final long MAX_ORDER_CODE = 9_007_199_254_740_991L;

    private final PayosProperties properties;
    private final PayosSignatureService signatureService;
    private final ProviderOrderCodeGenerator orderCodeGenerator;
    private final RestClient restClient;

    @Autowired
    public PayosAdapter(PayosProperties properties, PayosSignatureService signatureService,
                        ProviderOrderCodeGenerator orderCodeGenerator, RestClient.Builder restClientBuilder) {
        this(properties, signatureService, orderCodeGenerator, buildClient(properties, restClientBuilder));
    }

    // Cho test: truyền RestClient đã gắn MockRestServiceServer.
    PayosAdapter(PayosProperties properties, PayosSignatureService signatureService,
                 ProviderOrderCodeGenerator orderCodeGenerator, RestClient restClient) {
        this.properties = properties;
        this.signatureService = signatureService;
        this.orderCodeGenerator = orderCodeGenerator;
        this.restClient = restClient;
    }

    private static RestClient buildClient(PayosProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getApiUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.PAYOS;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public int getExpiryMinutes() {
        return properties.getExpiryMinutes();
    }

    // Tạo link thanh toán: kiểm tra số tiền -> cấp orderCode số -> ký -> POST -> kiểm tra response -> trả checkoutUrl.
    @Override
    public ProviderCheckoutResult processPayment(ProviderCheckoutRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("payOS provider is disabled");
        }
        long amount = validatedAmount(request);
        long orderCode = orderCodeGenerator.next();
        if (orderCode <= 0 || orderCode > MAX_ORDER_CODE) {
            throw new IllegalStateException("payOS order code is outside the supported range");
        }
        String description = description(request.getOrderId());
        int expiredAt = unixSeconds(request.getExpiresAt());
        String signature = signatureService.signPaymentRequest(
                amount, properties.getCancelUrl(), description, orderCode, properties.getReturnUrl());
        PayosCreateRequest body = new PayosCreateRequest(orderCode, amount, description,
                properties.getCancelUrl(), properties.getReturnUrl(), expiredAt, signature);

        PayosCreateResponse response = send(body);
        String checkoutUrl = validatedCheckoutUrl(response, orderCode);
        log.info("payOS payment link created orderCode={} transactionId={}", orderCode, request.getTransactionId());

        return ProviderCheckoutResult.builder()
                .transactionId(String.valueOf(request.getTransactionId()))
                .checkoutUrl(checkoutUrl)
                .expiresAt(request.getExpiresAt())
                .status(ProviderCheckoutStatus.PENDING)
                .providerOrderCode(orderCode)
                .build();
    }

    private PayosCreateResponse send(PayosCreateRequest body) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/v2/payment-requests")
                    .header("x-client-id", properties.getClientId())
                    .header("x-api-key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);
            if (properties.getPartnerCode() != null && !properties.getPartnerCode().isBlank()) {
                spec = spec.header("x-partner-code", properties.getPartnerCode());
            }
            return spec.body(body).retrieve().body(PayosCreateResponse.class);
        } catch (RestClientResponseException exception) {
            // 401 = sai clientId/apiKey, 429 = gọi quá nhiều... Chỉ ghi mã HTTP, không ghi body (có thể chứa dữ liệu nhạy cảm).
            log.error("payOS rejected the create request httpStatus={}", exception.getStatusCode().value());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "The payment provider rejected the request");
        } catch (RestClientException exception) {
            log.error("payOS is unreachable errorType={}", exception.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "The payment provider is temporarily unavailable", 5);
        }
    }

    // Chỉ tin response khi code "00", orderCode khớp request và checkoutUrl là https. Mọi bất thường đều là lỗi nhà cung cấp (502).
    private String validatedCheckoutUrl(PayosCreateResponse response, long orderCode) {
        if (response == null || !"00".equals(response.code()) || response.data() == null) {
            log.error("payOS returned an unsuccessful result code={}", response == null ? "NONE" : response.code());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "The payment provider could not create the payment");
        }
        PayosCreateResponse.Data data = response.data();
        String url = data.checkoutUrl();
        if (data.orderCode() == null || data.orderCode() != orderCode
                || url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            log.error("payOS response does not match the request orderCode={}", orderCode);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR", "The payment provider returned an invalid response");
        }
        return url;
    }

    private long validatedAmount(ProviderCheckoutRequest request) {
        if (request.getAmount().getCurrency() != Currency.VND) {
            throw new IllegalArgumentException("payOS only supports VND");
        }
        long amount;
        try {
            amount = request.getAmount().getAmount().longValueExact();
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be a whole VND amount");
        }
        if (amount < properties.getMinAmount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Amount must be at least " + properties.getMinAmount() + " VND");
        }
        return amount;
    }

    // Nội dung chuyển khoản: prefix + orderId, cắt theo giới hạn của kênh (mặc định 9 ký tự).
    private String description(Long orderId) {
        String text = properties.getDescriptionPrefix() + orderId;
        return text.length() <= properties.getDescriptionMaxLength()
                ? text
                : text.substring(0, properties.getDescriptionMaxLength());
    }

    // payOS nhận Unix timestamp GIÂY kiểu Int32.
    private int unixSeconds(OffsetDateTime time) {
        long seconds = time.toEpochSecond();
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalStateException("payOS expiredAt does not fit Int32");
        }
        return (int) seconds;
    }
}
