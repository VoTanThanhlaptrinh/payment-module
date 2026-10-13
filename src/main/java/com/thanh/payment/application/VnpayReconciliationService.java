package com.thanh.payment.application;

import com.thanh.payment.application.provider.VnpayQueryResult;
import com.thanh.payment.infrastructure.provider.vnpay.VnpayQueryDrClient;
import com.thanh.payment.domain.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Đối soát MỘT giao dịch: hỏi VNPay (QueryDR) rồi giao kết quả cho VnpayReconciliationProcessor áp dụng vào DB.
 *
 * Cố ý tách đôi: cuộc gọi mạng nằm NGOÀI transaction DB (không giữ khoá hàng trong lúc chờ VNPay trả lời, tối đa vài giây),
 * còn phần ghi DB nằm trong transaction ngắn của processor, tại đó mới khoá hàng và kiểm tra lại trạng thái.
 * Chỉ tạo khi reconciliation-enabled=true.
 */
@Service
@ConditionalOnProperty(prefix = "payment.providers.vnpay", name = "reconciliation-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VnpayReconciliationService {

    private final VnpayQueryDrClient transactionQuery;
    private final VnpayReconciliationProcessor processor;

    // Điểm vào cho scheduler. Lỗi mạng/timeout không được làm hỏng cả lô: đổi thành kết quả RETRY để lần sau thử lại.
    public VnpayReconciliationOutcome reconcile(Transaction transaction) {
        VnpayQueryResult result;
        try {
            // Gọi VNPay (có timeout connect/read ở VnpayQueryDrClient). Chưa giữ khoá DB nào.
            result = transactionQuery.query(transaction);
        } catch (RuntimeException exception) {
            log.warn("VNPay QueryDR transport failure transactionId={} errorType={}",
                    transaction.getId(), exception.getClass().getSimpleName());
            // Timeout/đứt kết nối/HTTP lỗi: giao dịch giữ nguyên PENDING, vẫn được tính là một lần thử.
            result = VnpayQueryResult.retry("TRANSPORT_ERROR", "VNPay QueryDR transport failure");
        }
        // Áp dụng kết quả trong một transaction DB riêng (khoá hàng, kiểm tra lại, cập nhật).
        VnpayReconciliationOutcome outcome = processor.apply(transaction.getId(), result);
        log.info("VNPay reconciliation transactionId={} attemptCode={} outcome={}",
                transaction.getId(), result.attemptCode(), outcome);
        return outcome;
    }
}
