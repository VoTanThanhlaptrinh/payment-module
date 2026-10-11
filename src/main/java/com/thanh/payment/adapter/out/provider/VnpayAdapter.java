package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.application.port.out.ProviderCheckoutStatus;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter đi RA phía VNPay: dựng URL thanh toán (hosted checkout) đã ký.
 *
 * Module KHÔNG BAO GIỜ nhận số thẻ/CVV: khách được redirect sang trang của VNPay để nhập, VNPay báo kết quả về bằng IPN.
 * Việc ký là tính toán cục bộ (không gọi mạng), nên có thể thực hiện khi đang giữ DB transaction.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(VnpayProperties.class)
public class VnpayAdapter implements PaymentProvider {

    // VNPay đọc/ghi thời gian theo múi giờ Việt Nam (không phải UTC), định dạng yyyyMMddHHmmss.
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VnpayProperties properties;
    private final VnpaySignatureService signatureService;

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.VNPAY;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    // Dựng URL thanh toán: tham số vnp_* -> canonical (sắp xếp + URL-encode) -> HMAC-SHA512 -> ghép vnp_SecureHash vào cuối.
    // Khách mở URL này sẽ thấy trang thanh toán VNPay; VNPay verify chữ ký bằng cùng secret nên URL bị sửa sẽ bị từ chối.
    @Override
    public ProviderCheckoutResult processPayment(ProviderCheckoutRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("VNPay provider is disabled");
        }
        // Bước 1: gom các tham số bắt buộc của VNPay (xem createParameters).
        Map<String, String> parameters = createParameters(request);
        // Bước 2: chuỗi canonical vừa là thứ được ký, vừa là query string của URL (VNPay tự dựng lại y hệt để so chữ ký).
        String canonical = signatureService.canonicalize(parameters);
        // Bước 3: URL cuối = payment-url + canonical + vnp_SecureHash. Tuyệt đối không ghi log URL này (chứa chữ ký).
        String checkoutUrl = properties.getPaymentUrl() + "?" + canonical
                + "&vnp_SecureHash=" + signatureService.sign(parameters);

        return ProviderCheckoutResult.builder()
                .transactionId(String.valueOf(request.getTransactionId()))
                .checkoutUrl(checkoutUrl)
                .expiresAt(request.getExpiresAt())
                .status(ProviderCheckoutStatus.PENDING)
                .build();
    }

    // Dựng bộ tham số gửi VNPay. Giữ thứ tự thêm vào cho dễ đọc; thứ tự ký do canonicalize() (TreeMap) quyết định.
    private Map<String, String> createParameters(ProviderCheckoutRequest request) {
        if (request.getAmount().getCurrency() != Currency.VND) {
            throw new IllegalArgumentException("VNPay only supports VND");
        }
        // vnp_Amount = số tiền VND x 100 (VNPay bỏ dấu thập phân). toBigIntegerExact ném lỗi nếu còn phần lẻ.
        BigInteger providerAmount = request.getAmount().getAmount().toBigIntegerExact()
                .multiply(BigInteger.valueOf(100));
        if (providerAmount.signum() <= 0 || providerAmount.toString().length() > 12) {
            throw new IllegalArgumentException("VNPay amount is outside the supported range");
        }
        // Thời gian lấy từ request (do PaymentService cấp từ Clock) để khớp với createdAt/expiredAt đã lưu trong DB.
        OffsetDateTime createdAt = request.getCreatedAt();
        OffsetDateTime expiresAt = request.getExpiresAt();
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("vnp_Version", properties.getVersion());
        parameters.put("vnp_Command", "pay");
        // vnp_TmnCode: mã merchant do VNPay cấp. Cùng giá trị này sẽ được kiểm tra lại khi nhận IPN.
        parameters.put("vnp_TmnCode", properties.getTmnCode());
        parameters.put("vnp_Amount", providerAmount.toString());
        parameters.put("vnp_CurrCode", "VND");
        // vnp_TxnRef = Transaction.id (KHÔNG phải orderId): mã duy nhất cho mỗi lần thử, và là khoá để IPN tìm lại giao dịch.
        parameters.put("vnp_TxnRef", String.valueOf(request.getTransactionId()));
        parameters.put("vnp_OrderInfo", "Thanh toan don hang " + request.getOrderId());
        parameters.put("vnp_OrderType", "other");
        // Nơi VNPay đưa trình duyệt của khách quay về (chỉ để hiển thị, không đổi trạng thái).
        parameters.put("vnp_ReturnUrl", properties.getReturnUrl());
        parameters.put("vnp_IpAddr", request.getClientIp());
        // Thời gian tạo và hạn thanh toán theo giờ Việt Nam. Quá vnp_ExpireDate khách không thanh toán được nữa (VNPay báo mã 11).
        parameters.put("vnp_CreateDate", createdAt.atZoneSameInstant(VNPAY_ZONE).format(VNPAY_TIME));
        parameters.put("vnp_ExpireDate", expiresAt.atZoneSameInstant(VNPAY_ZONE).format(VNPAY_TIME));
        parameters.put("vnp_Locale", "vn");
        return parameters;
    }
}
