package com.thanh.payment.infrastructure.provider.vnpay;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// Cấu hình VNPay bind từ payment.providers.vnpay.* (giá trị thật lấy từ biến môi trường VNPAY_*). Được validate khi khởi động: bật enabled mà cấu hình sai thì app không chạy.
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "payment.providers.vnpay")
public class VnpayProperties {
    private boolean enabled;

    private String paymentUrl;

    private String returnUrl;

    private String ipnUrl;

    private String tmnCode;

    private String hashSecret;

    private String version = "2.1.0";

    @Min(1)
    private Integer expiryMinutes = 15;

    private String queryApiUrl;

    private String merchantIp;

    private boolean reconciliationEnabled;

    private Duration reconciliationInitialDelay = Duration.ofSeconds(30);

    private Duration reconciliationInterval = Duration.ofMinutes(5);

    // Sau expired_at + khoảng này, nếu VNPay vẫn báo không có giao dịch (mã 91) thì chuyển giao dịch sang EXPIRED. Chừa chỗ cho lệch đồng hồ.
    private Duration reconciliationNotFoundGrace = Duration.ofMinutes(5);

    @Min(1)
    private Integer reconciliationMaxAttempts = 12;

    @Min(1)
    private Integer reconciliationBatchSize = 50;

    private Duration queryConnectTimeout = Duration.ofSeconds(3);

    private Duration queryReadTimeout = Duration.ofSeconds(5);

    // Chỉ kiểm tra khi enabled=true. Gộp thành một điều kiện nên lỗi không nêu rõ trường sai: cần soát paymentUrl, returnUrl, ipnUrl (bắt buộc https), tmnCode, hashSecret, version 2.1.0, expiryMinutes; và khi bật đối soát thì thêm queryApiUrl (https), merchantIp (địa chỉ IP) và các thời gian chờ.
    @AssertTrue(message = "Enabled VNPay configuration requires valid URLs, TmnCode, secret and version 2.1.0")
    public boolean isValidWhenEnabled() {
        if (!enabled) {
            return true;
        }
        return isHttpUrl(paymentUrl)
                && isHttpUrl(returnUrl)
                && isHttpsUrl(ipnUrl)
                && hasText(tmnCode)
                && hasText(hashSecret)
                && "2.1.0".equals(version)
                && expiryMinutes != null
                && expiryMinutes > 0
                && isValidReconciliationConfiguration();
    }

    private boolean isValidReconciliationConfiguration() {
        if (!reconciliationEnabled) {
            return true;
        }
        return isHttpsUrl(queryApiUrl)
                && isIpAddress(merchantIp)
                && isPositive(reconciliationInitialDelay)
                && isPositive(reconciliationInterval)
                && isPositive(reconciliationNotFoundGrace)
                && reconciliationMaxAttempts != null
                && reconciliationMaxAttempts > 0
                && reconciliationBatchSize != null
                && reconciliationBatchSize > 0
                && isPositive(queryConnectTimeout)
                && isPositive(queryReadTimeout);
    }

    private boolean isHttpsUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean isIpAddress(String value) {
        if (!hasText(value) || value.length() > 45) {
            return false;
        }
        if (value.contains(":")) {
            try {
                return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address;
            } catch (java.net.UnknownHostException ignored) {
                return false;
            }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}")) {
                return false;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isHttpUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
