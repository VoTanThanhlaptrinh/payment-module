package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;

@Component
@RequiredArgsConstructor
public class TransactionRepositoryImpl implements TransactionRepository {

    private final TransactionJpaRepository jpaRepository;

    @Override
    public Transaction save(Transaction transaction) {
        return jpaRepository.save(transaction);
    }

    @Override
    public Transaction saveAndFlush(Transaction transaction) {
        return jpaRepository.saveAndFlush(transaction);
    }

    @Override
    public Optional<Transaction> findById(Long id) {
        return jpaRepository.findById(id);
    }

    // Khoá ghi hàng: chuyển tiếp sang query PESSIMISTIC_WRITE ở TransactionJpaRepository.
    @Override
    public Optional<Transaction> findByIdForUpdate(Long id) {
        return jpaRepository.findByIdForUpdate(id);
    }

    @Override
    public Optional<Transaction> findPendingChargeByPaymentId(Long paymentId) {
        return jpaRepository.findFirstByPaymentIdAndTypeAndStatusOrderByCreatedAtDesc(
                paymentId, TransactionType.CHARGE, TransactionStatus.PENDING);
    }

    @Override
    public Optional<Transaction> findByProviderTransactionId(String providerTransactionId) {
        return jpaRepository.findByProviderTransactionId(providerTransactionId);
    }

    // Lấy CHARGE mới nhất theo created_at (dùng khi replay idempotency).
    @Override
    public Optional<Transaction> findLatestChargeByPaymentId(Long paymentId) {
        return jpaRepository.findFirstByPaymentIdAndTypeOrderByCreatedAtDesc(paymentId, TransactionType.CHARGE);
    }

    // Truyền cố định type=CHARGE và status=PENDING; giới hạn số dòng bằng PageRequest (batch-size).
    @Override
    public List<Transaction> findReconciliationCandidates(OffsetDateTime now, int limit) {
        return jpaRepository.findReconciliationCandidates(
                TransactionType.CHARGE,
                TransactionStatus.PENDING,
                now,
                PageRequest.of(0, limit));
    }
}
