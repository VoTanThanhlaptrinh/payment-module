package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Ký và kiểm tra chữ ký VNPay bằng HMAC-SHA512 với hash secret của merchant.
 *
 * Hai bên phải dựng CÙNG MỘT chuỗi để ký, nếu lệch một ký tự là ra chữ ký hoàn toàn khác:
 *   bỏ tham số rỗng -> sắp theo tên (A-Z) -> URL-encode kiểu Java (dấu cách thành '+') -> nối "k=v" bằng '&'.
 * Class này dùng chung cho URL gửi đi (sign) và IPN nhận về (verify).
 */
@Component
@RequiredArgsConstructor
public class VnpaySignatureService {

    private final VnpayProperties properties;

    // Dựng chuỗi canonical. TreeMap tự sắp theo tên tham số; giá trị null/rỗng bị bỏ (VNPay không ký tham số rỗng).
    public String canonicalize(Map<String, String> parameters) {
        return new TreeMap<>(parameters).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    // Chữ ký (hex) của một bộ tham số.
    public String sign(Map<String, String> parameters) {
        return signRaw(canonicalize(parameters));
    }

    // HMAC-SHA512 của chuỗi bất kỳ, trả về hex 128 ký tự. Secret lấy từ cấu hình (biến môi trường), không bao giờ log.
    public String signRaw(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(properties.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot calculate VNPay signature", exception);
        }
    }

    // Kiểm tra chữ ký của IPN: tính lại rồi so sánh với chữ ký VNPay gửi.
    // Hai điểm an toàn: (1) chữ ký phải đúng 128 ký tự hex mới xét tiếp; (2) so sánh bằng MessageDigest.isEqual
    // (thời gian hằng số) để kẻ tấn công không đoán dần từng ký tự qua độ trễ phản hồi (timing attack).
    public boolean verify(Map<String, String> parameters, String suppliedSignature) {
        if (suppliedSignature == null || !suppliedSignature.matches("(?i)[0-9a-f]{128}")) {
            return false;
        }
        byte[] expected = sign(parameters).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    // Như verify() nhưng cho chuỗi đã dựng sẵn (QueryDR dùng định dạng nối bằng "|" thay vì query string).
    public boolean verifyRaw(String value, String suppliedSignature) {
        if (suppliedSignature == null || !suppliedSignature.matches("(?i)[0-9a-f]{128}")) {
            return false;
        }
        byte[] expected = signRaw(value).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    // URLEncoder của Java: khoảng trắng thành "+", ký tự đặc biệt thành %XX. VNPay ký đúng theo kiểu này.
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
