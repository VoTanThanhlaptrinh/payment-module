package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.dto.VnpayQueryDecision;
import com.thanh.payment.application.dto.VnpayQueryResult;
import com.thanh.payment.application.port.out.VnpayTransactionQuery;
import com.thanh.payment.domain.model.Transaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Client gọi API QueryDR của VNPay để hỏi trạng thái thật của một giao dịch (dùng khi mất IPN).
 *
 * Có timeout riêng (connect 3s, read 5s mặc định) để một lần VNPay chậm không treo cả lô đối soát.
 * Mọi response đều được kiểm tra chặt (chữ ký, TmnCode, TxnRef, số tiền, loại giao dịch) trước khi tin.
 */
@Component
@ConditionalOnProperty(prefix = "payment.providers.vnpay", name = "reconciliation-enabled", havingValue = "true")
public class VnpayQueryDrClient implements VnpayTransactionQuery {

    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VnpayProperties properties;
    private final VnpayQueryDrCodec codec;
    private final Clock clock;
    private final RestClient restClient;

    public VnpayQueryDrClient(
            VnpayProperties properties,
            VnpayQueryDrCodec codec,
            Clock clock,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.codec = codec;
        this.clock = clock;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getQueryConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getQueryReadTimeout());
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
    }

    // Gọi VNPay: dựng request ký sẵn -> POST JSON -> đọc response -> validateAndMap.
    // Ném RuntimeException khi lỗi mạng/timeout/HTTP 5xx; VnpayReconciliationService bắt và coi là lần thử lỗi (TRANSPORT_ERROR).
    @Override
    public VnpayQueryResult query(Transaction transaction) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // vnp_TxnRef = Transaction.id, đúng mã đã gửi lúc tạo URL thanh toán.
        String txnRef = String.valueOf(transaction.getId());
        String orderInfo = "Truy van giao dich " + txnRef;
        VnpayQueryDrRequest request = codec.signedRequest(
                UUID.randomUUID().toString().replace("-", ""),
                txnRef,
                // vnp_TransactionDate: thời điểm tạo giao dịch (VNPay yêu cầu để tra đúng giao dịch).
                transaction.getCreatedAt().atZoneSameInstant(VNPAY_ZONE).format(VNPAY_TIME),
                now.atZoneSameInstant(VNPAY_ZONE).format(VNPAY_TIME),
                orderInfo);

        VnpayQueryDrResponse response = restClient.post()
                .uri(properties.getQueryApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(VnpayQueryDrResponse.class);
        return validateAndMap(transaction, response);
    }

    // Kiểm tra response rồi đổi thành quyết định nghiệp vụ. Mọi bất thường đều thành RETRY (không đổi trạng thái):
    //   - response rỗng                                   => EMPTY_RESPONSE
    //   - chữ ký sai                                      => INVALID_SIGNATURE
    //   - khác TmnCode/TxnRef/số tiền/loại giao dịch       => RESPONSE_MISMATCH (chống nhầm giao dịch hoặc dữ liệu giả)
    //   - ResponseCode khác 00 (VNPay từ chối yêu cầu)     => API_<mã>
    // Khi hợp lệ mới xét vnp_TransactionStatus.
    VnpayQueryResult validateAndMap(Transaction transaction, VnpayQueryDrResponse response) {
        if (response == null) {
            return VnpayQueryResult.retry("EMPTY_RESPONSE", "VNPay QueryDR returned an empty response");
        }
        if (!codec.verifyResponse(response)) {
            return VnpayQueryResult.retry("INVALID_SIGNATURE", "VNPay QueryDR signature is invalid");
        }
        // Mã 91 = VNPay không có giao dịch này. Response này không mang đủ số tiền/loại giao dịch nên không qua bước so khớp bên dưới;
        // nếu có TmnCode/TxnRef thì vẫn phải khớp để không nhầm sang giao dịch khác.
        if ("91".equals(response.responseCode())) {
            boolean otherMerchant = hasText(response.tmnCode()) && !properties.getTmnCode().equals(response.tmnCode());
            boolean otherTransaction = hasText(response.txnRef()) && !String.valueOf(transaction.getId()).equals(response.txnRef());
            if (otherMerchant || otherTransaction) {
                return VnpayQueryResult.retry("RESPONSE_MISMATCH", "VNPay QueryDR response does not match the charge");
            }
            return new VnpayQueryResult(VnpayQueryDecision.NOT_FOUND, attemptCode("API", "91"), null, null, response.message());
        }
        if (!properties.getTmnCode().equals(response.tmnCode())
                || !String.valueOf(transaction.getId()).equals(response.txnRef())
                || !expectedAmount(transaction).equals(response.amount())
                || !"01".equals(response.transactionType())
                || (hasText(response.command()) && !"querydr".equals(response.command()))) {
            return VnpayQueryResult.retry("RESPONSE_MISMATCH", "VNPay QueryDR response does not match the charge");
        }
        if (!"00".equals(response.responseCode())) {
            return VnpayQueryResult.retry(attemptCode("API", response.responseCode()), "VNPay QueryDR request was rejected");
        }

        // vnp_TransactionStatus: 00 thành công | 01 chưa có kết quả | 02 thất bại | 04, 07 nghi ngờ/cần xem xét | khác => RETRY.
        VnpayQueryDecision decision = switch (value(response.transactionStatus())) {
            case "00" -> VnpayQueryDecision.SUCCESS;
            case "01" -> VnpayQueryDecision.PENDING;
            case "02" -> VnpayQueryDecision.FAILED;
            case "04", "07" -> VnpayQueryDecision.REVIEW_REQUIRED;
            default -> VnpayQueryDecision.RETRY;
        };
        return new VnpayQueryResult(
                decision,
                attemptCode("TXN", response.transactionStatus()),
                response.transactionStatus(),
                response.transactionNo(),
                response.message(),
                response.payDate(),
                response.bankCode());
    }

    private String expectedAmount(Transaction transaction) {
        BigInteger amount = transaction.getAmount().getAmount().toBigIntegerExact()
                .multiply(BigInteger.valueOf(100));
        return amount.toString();
    }

    private String attemptCode(String prefix, String code) {
        String safeCode = hasText(code) ? code : "UNKNOWN";
        return prefix + "_" + safeCode;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
