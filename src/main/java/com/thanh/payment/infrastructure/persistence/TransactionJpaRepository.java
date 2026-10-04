package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionJpaRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t WHERE t.paymentId = :paymentId AND t.type = :type AND t.status = :status ORDER BY t.createdAt DESC LIMIT 1")
    Optional<Transaction> findFirstByPaymentIdAndTypeAndStatus(
            @Param("paymentId") Long paymentId,
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status
    );

    Optional<Transaction> findByProviderTransactionId(String providerTransactionId);
}
