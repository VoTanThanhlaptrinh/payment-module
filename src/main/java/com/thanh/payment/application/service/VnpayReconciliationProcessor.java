package com.thanh.payment.application.service;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.dto.VnpayQueryResult;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Áp dụng kết quả QueryDR vào DB trong một transaction ngắn.
 *
 * Vì QueryDR chạy ngoài transaction, trong lúc chờ VNPay trả lời một IPN có thể đã kịp ghi kết quả. Do đó processor
 * khoá hàng rồi KIỂM TRA LẠI trạng thái; nếu giao dịch đã kết thúc thì bỏ qua (ALREADY_TERMINAL), không bao giờ ghi đè.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VnpayReconciliationProcessor {

    private final TransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final VnpayProperties properties;
    private final Clock clock;

    // Cả quá trình (khoá hàng, ghi lần thử, đổi trạng thái) là một transaction.
    @Transactional
    public VnpayReconciliationOutcome apply(Long transactionId, VnpayQueryResult result) {
        // Khoá hàng, rồi mới quyết định dựa trên trạng thái MỚI NHẤT trong DB (không dựa vào bản đã đọc trước khi gọi VNPay).
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (transaction == null) {
            return VnpayReconciliationOutcome.INVALID_LOCAL_STATE;
        }
        // IPN (hoặc lần đối soát khác) đã xử lý xong trong lúc ta chờ VNPay: bỏ qua, không ghi đè và cũng không tính là một lần thử.
        if (transaction.isTerminal()) {
            return VnpayReconciliationOutcome.ALREADY_TERMINAL;
        }
        // Đã hết lượt từ trước: không làm gì thêm.
        if (transaction.isReconciliationExhausted()) {
            return VnpayReconciliationOutcome.EXHAUSTED;
        }
        Payment payment = paymentRepository.findById(transaction.getPaymentId()).orElse(null);
        if (payment == null
                || !payment.isPending()
                || payment.getMethod() != PaymentMethodType.VNPAY
                || transaction.getType() != TransactionType.CHARGE) {
            return VnpayReconciliationOutcome.INVALID_LOCAL_STATE;
        }

        // Ghi nhận lần thử TRƯỚC khi áp dụng kết quả: attempts++, lưu mã kết quả, đặt lịch lần sau (hoặc bật exhausted nếu hết lượt).
        OffsetDateTime attemptedAt = OffsetDateTime.now(clock);
        transaction.recordReconciliationAttempt(
                attemptedAt,
                result.attemptCode(),
                attemptedAt.plus(properties.getReconciliationInterval()),
                properties.getReconciliationMaxAttempts());

        // Áp kết quả QueryDR:
        //   SUCCESS         => payment COMPLETED, transaction SUCCESS
        //   FAILED          => FAILED
        //   REVIEW_REQUIRED => REVIEW_REQUIRED
        //   NOT_FOUND       => sau expired_at + grace: EXPIRED (order hết bị khoá); trước đó giữ PENDING và thử lại
        //   PENDING/RETRY   => chưa có kết quả: không đổi trạng thái, chỉ giữ lần thử đã ghi
        boolean updated = switch (result.decision()) {
            case SUCCESS -> {
                payment.markCompleted();
                transaction.markSuccess(result.providerTransactionId(), "00");
                yield true;
            }
            case FAILED -> {
                payment.markFailed();
                transaction.markFailed(result.providerTransactionId(), result.transactionStatus(),
                        "VNPay QueryDR reported a failed charge");
                yield true;
            }
            case REVIEW_REQUIRED -> {
                payment.markReviewRequired();
                transaction.markReviewRequired(result.providerTransactionId(), result.transactionStatus(),
                        "VNPay QueryDR requires manual review");
                yield true;
            }
            case NOT_FOUND -> {
                // VNPay không có giao dịch. Chỉ kết luận "hết hạn" khi đã qua hạn thanh toán + thời gian chờ; trước đó có thể VNPay chưa ghi nhận kịp.
                if (attemptedAt.isBefore(transaction.getExpiredAt().plus(properties.getReconciliationNotFoundGrace()))) {
                    yield false;
                }
                payment.markExpired();
                transaction.markExpired(null, "91", "VNPay has no record of this charge after expiry");
                yield true;
            }
            case PENDING, RETRY -> false;
        };

        if (updated) {
            paymentRepository.save(payment);
        }
        // Luôn lưu transaction vì các trường đối soát (attempts, next_reconciliation_at...) đã đổi, kể cả khi trạng thái chưa đổi.
        transactionRepository.save(transaction);
        if (updated) {
            return VnpayReconciliationOutcome.UPDATED;
        }
        // Hết lượt mà vẫn chưa rõ: log lỗi và giữ PENDING cho người xử lý thủ công (không tự đoán FAILED vì tiền có thể đã bị trừ).
        if (transaction.isReconciliationExhausted()) {
            log.error("VNPay reconciliation exhausted transactionId={} attemptCode={} attempts={}",
                    transactionId, result.attemptCode(), transaction.getReconciliationAttempts());
            return VnpayReconciliationOutcome.EXHAUSTED;
        }
        return VnpayReconciliationOutcome.RETRY_SCHEDULED;
    }
}
