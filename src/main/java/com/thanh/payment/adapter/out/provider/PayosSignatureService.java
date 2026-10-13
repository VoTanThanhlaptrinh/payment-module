package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.PayosProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Ký và kiểm tra chữ ký payOS: HMAC-SHA256 bằng checksum key của kênh thanh toán, kết quả hex chữ thường.
 * Khác VNPay (HMAC-SHA512, URL-encode): dữ liệu ký ở đây là các giá trị THÔ, không encode, ghép "key=value" bằng "&" theo thứ tự alphabet.
 */
@Component
@RequiredArgsConstructor
public class PayosSignatureService {

    private final PayosProperties properties;

    // Chữ ký của request tạo link: amount, cancelUrl, description, orderCode, returnUrl (đúng thứ tự alphabet theo tài liệu payOS).
    public String signPaymentRequest(long amount, String cancelUrl, String description, long orderCode, String returnUrl) {
        return sign("amount=" + amount
                + "&cancelUrl=" + cancelUrl
                + "&description=" + description
                + "&orderCode=" + orderCode
                + "&returnUrl=" + returnUrl);
    }

    public String sign(String data) {
        String key = properties.getChecksumKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("payOS checksum key is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    // So sánh hằng thời gian, dùng khi verify webhook ở bước sau.
    public boolean verify(String data, String suppliedSignature) {
        if (suppliedSignature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(data).getBytes(StandardCharsets.UTF_8),
                suppliedSignature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
