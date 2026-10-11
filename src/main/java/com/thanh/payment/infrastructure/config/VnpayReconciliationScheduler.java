package com.thanh.payment.infrastructure.config;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.service.VnpayReconciliationService;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Bộ lập lịch đối soát giao dịch VNPay bị mất IPN. Chỉ được tạo khi reconciliation-enabled=true.
 *
 * Mỗi chu kỳ (mặc định 5 phút, chờ 30 giây sau khi khởi động):
 *   1. Lấy lô CHARGE còn PENDING đã tới next_reconciliation_at.
 *   2. Với từng giao dịch, VnpayReconciliationService hỏi VNPay (QueryDR) rồi VnpayReconciliationProcessor
 *      cập nhật DB trong một transaction riêng.
 * Hết số lần tối đa (mặc định 12) mà vẫn chưa rõ kết quả thì giao dịch được đánh dấu exhausted và giữ PENDING.
 */
@Component
@ConditionalOnProperty(prefix = "payment.providers.vnpay", name = "reconciliation-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class VnpayReconciliationScheduler {

    private final TransactionRepository transactionRepository;
    private final VnpayReconciliationService reconciliationService;
    private final VnpayProperties properties;
    private final Clock clock;

    // fixedDelay: chu kỳ tính từ lúc lần chạy trước KẾT THÚC, nên hai lần chạy không bao giờ chồng nhau.
    @Scheduled(
            initialDelayString = "${payment.providers.vnpay.reconciliation-initial-delay:30s}",
            fixedDelayString = "${payment.providers.vnpay.reconciliation-interval:5m}")
    // Điểm vào của mỗi chu kỳ. Lỗi ở một giao dịch được xử lý trong reconcile() nên không làm hỏng cả lô.
    public void reconcileDueTransactions() {
        List<Transaction> candidates = transactionRepository.findReconciliationCandidates(
                OffsetDateTime.now(clock), properties.getReconciliationBatchSize());
        if (!candidates.isEmpty()) {
            log.info("VNPay reconciliation batch candidates={}", candidates.size());
        }
        for (Transaction transaction : candidates) {
            // Cô lập từng giao dịch: lỗi ngoài dự kiến ở một giao dịch (DB lỗi, xung đột version) không được chặn các giao dịch sau trong lô.
            try {
                reconciliationService.reconcile(transaction);
            } catch (RuntimeException exception) {
                log.error("VNPay reconciliation failed transactionId={} errorType={}",
                        transaction.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
