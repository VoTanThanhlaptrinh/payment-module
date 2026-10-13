package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findFirstByPaymentIdAndTypeAndStatusOrderByCreatedAtDesc(
            Long paymentId, TransactionType type, TransactionStatus status);

    Optional<Transaction> findByProviderTransactionId(String providerTransactionId);

    // Đọc kèm khoá ghi hàng. Bắt buộc dùng khi xử lý IPN/đối soát để hai luồng không cùng ghi một giao dịch.
    // PESSIMISTIC_WRITE => Hibernate sinh "select ... for update": giữ khoá hàng tới khi transaction kết thúc. Hai IPN đến cùng lúc sẽ xếp hàng ở đây.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    Optional<Transaction> findFirstByPaymentIdAndTypeOrderByCreatedAtDesc(Long paymentId, TransactionType type);

    // Điều kiện đối soát: CHARGE còn PENDING, chưa hết lượt, và đã tới giờ (next_reconciliation_at <= now). Ưu tiên giao dịch đến hạn sớm nhất.
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.type = :type
              AND t.status = :status
              AND t.reconciliationExhausted = false
              AND t.nextReconciliationAt IS NOT NULL
              AND t.nextReconciliationAt <= :now
            ORDER BY t.nextReconciliationAt ASC
            """)
    List<Transaction> findDueForReconciliation(
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("now") OffsetDateTime now,
            Pageable pageable);

    // CHARGE PENDING của payment (lần thử thanh toán đang chờ kết quả).
    default Optional<Transaction> findPendingChargeByPaymentId(Long paymentId) {
        return findFirstByPaymentIdAndTypeAndStatusOrderByCreatedAtDesc(
                paymentId, TransactionType.CHARGE, TransactionStatus.PENDING);
    }

    // Transaction CHARGE mới nhất của payment. Dùng khi replay idempotency để trả lại checkoutUrl cũ.
    default Optional<Transaction> findLatestChargeByPaymentId(Long paymentId) {
        return findFirstByPaymentIdAndTypeOrderByCreatedAtDesc(paymentId, TransactionType.CHARGE);
    }

    // Các CHARGE còn PENDING, chưa exhausted, có next_reconciliation_at <= now (tối đa limit dòng). Đầu vào của scheduler đối soát.
    default List<Transaction> findReconciliationCandidates(OffsetDateTime now, int limit) {
        return findDueForReconciliation(
                TransactionType.CHARGE, TransactionStatus.PENDING, now, PageRequest.of(0, limit));
    }
}
