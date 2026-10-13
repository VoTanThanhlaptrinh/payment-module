package com.thanh.payment.application.service;

import com.thanh.payment.adapter.out.provider.VnpayIpnVerifier;
import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.dto.webhook.VnpayIpnData;
import com.thanh.payment.application.dto.webhook.VnpayIpnResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Cửa vào xử lý IPN của VNPay (server-to-server). Đây là nguồn sự thật về kết quả thanh toán.
 *
 * Thứ tự cố ý: mọi kiểm tra rẻ và không cần DB làm TRƯỚC, chỉ khi hợp lệ mới gọi VnpayIpnProcessor (có DB):
 *   1. VNPay đã bật cấu hình?
 *   2. Chữ ký HMAC đúng? (sai => 97, hoặc 99 nếu dữ liệu méo; CHƯA chạm DB nên request giả không gây tải)
 *   3. Đủ tham số bắt buộc và đúng TmnCode của merchant?
 *   4. Xử lý nghiệp vụ trong một transaction DB (khoá hàng, kiểm tiền, chuyển trạng thái).
 * Mọi lỗi bất ngờ đều được bắt và đổi thành RspCode 99; luôn trả HTTP 200 cho VNPay.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VnpayIpnService {

    private final VnpayIpnVerifier verifier;
    private final VnpayProperties properties;
    private final VnpayIpnProcessor processor;

    // Điểm vào xử lý IPN; rawParameters là nguyên văn query string VNPay gửi tới.
    public VnpayIpnResponse process(Map<String, List<String>> rawParameters) {
        // Provider tắt thì không nhận IPN: trả 99, không đụng gì.
        if (!properties.isEnabled()) {
            return VnpayIpnResponse.invalidRequest();
        }
        // Bước 2: gom tham số vnp_*, bỏ vnp_SecureHash rồi tính lại HMAC-SHA512 để so với chữ ký VNPay gửi. Hoàn toàn trong bộ nhớ, chưa có truy vấn DB nào.
        VnpayIpnData data = verifier.verify(rawParameters);
        // Chữ ký sai: nếu dữ liệu méo thì 99, còn lại 97. Chỉ ghi log cảnh báo (không bao giờ log chữ ký hay tham số đầy đủ).
        if (!data.signatureValid()) {
            VnpayIpnResponse response = data.malformed()
                    ? VnpayIpnResponse.invalidRequest()
                    : VnpayIpnResponse.invalidSignature();
            log.warn("VNPay IPN rejected before database lookup rspCode={}", response.rspCode());
            return response;
        }
        // Bước 3: chữ ký đúng nhưng thiếu tham số bắt buộc, hoặc TmnCode không phải của merchant này (IPN của merchant khác) => 99.
        if (data.malformed() || !properties.getTmnCode().equals(data.get("vnp_TmnCode"))) {
            log.warn("VNPay IPN rejected after signature verification rspCode=99");
            return VnpayIpnResponse.invalidRequest();
        }
        // Bước 4: giao cho processor xử lý nghiệp vụ trong transaction DB.
        try {
            VnpayIpnResponse response = processor.processVerified(data);
            log.info("VNPay IPN txnRef={} responseCode={} transactionStatus={} rspCode={}",
                    data.get("vnp_TxnRef"),
                    data.get("vnp_ResponseCode"),
                    data.get("vnp_TransactionStatus"),
                    response.rspCode());
            return response;
        } catch (RuntimeException exception) {
            // Lỗi ngoài dự kiến (DB lỗi, xung đột version...): chỉ log loại lỗi, trả 99 để VNPay gửi lại IPN sau. Transaction đã rollback nên DB không bị đổi dở dang.
            log.error("VNPay IPN processing failed txnRef={} errorType={}",
                    data.get("vnp_TxnRef"), exception.getClass().getSimpleName());
            return VnpayIpnResponse.invalidRequest();
        }
    }
}
