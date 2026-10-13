package com.thanh.payment.infrastructure.provider.vnpay;

import com.thanh.payment.api.dto.webhook.VnpayIpnData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bước đầu tiên khi nhận IPN: chuẩn hoá tham số và kiểm tra chữ ký, hoàn toàn trong bộ nhớ (chưa đụng DB).
 * Kết quả VnpayIpnData cho biết: chữ ký đúng không, dữ liệu có méo/thiếu không, và các tham số vnp_* đã làm phẳng.
 */
@Component
@RequiredArgsConstructor
public class VnpayIpnVerifier {

    // Các tham số bắt buộc phải có trong mọi IPN hợp lệ. Thiếu một cái => malformed => RspCode 99.
    private static final Set<String> REQUIRED = Set.of(
            "vnp_TmnCode", "vnp_Amount", "vnp_TxnRef",
            "vnp_ResponseCode", "vnp_TransactionStatus", "vnp_TransactionNo", "vnp_SecureHash");

    private final VnpaySignatureService signatureService;

    // rawParameters là query string thô (mỗi tên có thể có nhiều giá trị).
    public VnpayIpnData verify(Map<String, List<String>> rawParameters) {
        if (rawParameters == null || rawParameters.isEmpty()) {
            return new VnpayIpnData(false, true, Map.of());
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : rawParameters.entrySet()) {
            // Tham số xuất hiện nhiều lần (vd vnp_Amount=1&vnp_Amount=2) là thủ đoạn gây nhập nhằng: từ chối ngay.
            if (entry.getValue() == null || entry.getValue().size() != 1) {
                return new VnpayIpnData(false, true, Map.of());
            }
            // Chỉ các tham số vnp_* tham gia ký; tham số khác (do proxy/tunnel thêm vào) bị bỏ qua.
            if (!entry.getKey().startsWith("vnp_")) {
                continue;
            }
            String value = entry.getValue().get(0);
            flattened.put(entry.getKey(), value == null ? "" : value);
        }

        if (flattened.isEmpty()) {
            return new VnpayIpnData(false, true, Map.of());
        }

        // Chữ ký VNPay gửi. Khi tính lại, phải loại vnp_SecureHash (và vnp_SecureHashType) khỏi dữ liệu được ký.
        String suppliedSignature = flattened.get("vnp_SecureHash");
        Map<String, String> signedFields = new LinkedHashMap<>(flattened);
        signedFields.remove("vnp_SecureHash");
        signedFields.remove("vnp_SecureHashType");
        // So sánh hằng thời gian (xem VnpaySignatureService#verify).
        boolean signatureValid = signatureService.verify(signedFields, suppliedSignature);
        if (!signatureValid) {
            return new VnpayIpnData(false, false, Map.copyOf(flattened));
        }
        // Chữ ký đúng rồi mới kiểm tra đủ tham số bắt buộc.
        boolean malformed = REQUIRED.stream()
                .anyMatch(name -> flattened.get(name) == null || flattened.get(name).isBlank());
        return new VnpayIpnData(true, malformed, Map.copyOf(flattened));
    }
}
