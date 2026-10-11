package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Transaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository {
    Transaction save(Transaction transaction);

    Transaction saveAndFlush(Transaction transaction);

    Optional<Transaction> findById(Long id);

    // Đọc kèm khoá ghi hàng (SELECT ... FOR UPDATE). Bắt buộc dùng khi xử lý IPN/đối soát để hai luồng không cùng ghi một giao dịch.
    Optional<Transaction> findByIdForUpdate(Long id);

    Optional<Transaction> findPendingChargeByPaymentId(Long paymentId);

    Optional<Transaction> findByProviderTransactionId(String providerTransactionId);

    // Transaction CHARGE mới nhất của payment. Dùng khi replay idempotency để trả lại checkoutUrl cũ.
    Optional<Transaction> findLatestChargeByPaymentId(Long paymentId);

    // Các CHARGE còn PENDING, chưa exhausted, có next_reconciliation_at <= now (tối đa limit dòng). Đầu vào của scheduler đối soát.
    List<Transaction> findReconciliationCandidates(OffsetDateTime now, int limit);
}
