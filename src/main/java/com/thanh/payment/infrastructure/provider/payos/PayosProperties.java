package com.thanh.payment.infrastructure.provider.payos;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Cấu hình payOS bind từ payment.providers.payos.* (giá trị thật lấy từ biến môi trường PAYOS_*). Được validate khi khởi động: bật enabled mà cấu hình sai thì app không chạy.
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "payment.providers.payos")
public class PayosProperties {
    private boolean enabled;

    // Ba khoá lấy ở my.payos.vn > Kênh thanh toán. clientId + apiKey xác thực request; checksumKey dùng để ký request và verify webhook. Không bao giờ ghi log.
    private String clientId;

    private String apiKey;

    private String checksumKey;

    // Tuỳ chọn: mã đối tác khi tham gia chương trình tích hợp của payOS (header x-partner-code).
    private String partnerCode;

    private String apiUrl = "https://api-merchant.payos.vn";

    // payOS đưa trình duyệt khách về đây khi thanh toán xong / khi khách bấm huỷ (chỉ để hiển thị, không đổi trạng thái).
    private String returnUrl;

    private String cancelUrl;

    private Integer expiryMinutes = 15;

    // Mức tối thiểu cho một giao dịch; kiểm tra lại với kênh thanh toán của bạn trên my.payos.vn.
    private long minAmount = 2000;

    // Nội dung chuyển khoản = prefix + orderId, cắt về descriptionMaxLength. payOS giới hạn 9 ký tự với tài khoản ngân hàng không liên kết qua payOS (25 nếu liên kết qua payOS).
    private String descriptionPrefix = "DH";

    private int descriptionMaxLength = 9;

    // Gọi payOS diễn ra trong DB transaction tạo payment (timeout payment.idempotency.max-processing-time-seconds, mặc định 10s): connect + read phải nhỏ hơn.
    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(5);

    // Chỉ kiểm tra khi enabled=true. Gộp thành một điều kiện nên lỗi không nêu rõ trường sai: cần soát clientId, apiKey, checksumKey, apiUrl (https), returnUrl, cancelUrl, expiryMinutes, minAmount, description và timeouts.
    @AssertTrue(message = "Enabled payOS configuration requires client id, api key, checksum key, https API URL, return/cancel URLs and valid limits")
    public boolean isValidWhenEnabled() {
        if (!enabled) {
            return true;
        }
        return hasText(clientId)
                && hasText(apiKey)
                && hasText(checksumKey)
                && isUrl(apiUrl, true)
                && isUrl(returnUrl, false)
                && isUrl(cancelUrl, false)
                && expiryMinutes != null
                && expiryMinutes > 0
                && minAmount > 0
                && descriptionPrefix != null
                && descriptionPrefix.matches("[A-Za-z0-9 ]{0,9}")
                && descriptionMaxLength >= 1
                && descriptionMaxLength <= 25
                && isPositive(connectTimeout)
                && isPositive(readTimeout);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean isUrl(String value, boolean httpsOnly) {
        if (!hasText(value)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            return uri.getHost() != null && (https || (!httpsOnly && http));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
