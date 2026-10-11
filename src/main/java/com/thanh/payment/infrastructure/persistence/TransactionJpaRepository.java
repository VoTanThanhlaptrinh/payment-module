package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.OffsetDateTime;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;

@Repository
public interface TransactionJpaRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findFirstByPaymentIdAndTypeAndStatusOrderByCreatedAtDesc(
            Long paymentId, TransactionType type, TransactionStatus status);

    Optional<Transaction> findByProviderTransactionId(String providerTransactionId);

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
    List<Transaction> findReconciliationCandidates(
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("now") OffsetDateTime now,
            Pageable pageable);
}
