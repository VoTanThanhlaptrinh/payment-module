package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TransactionRepositoryImpl implements TransactionRepository {

    private final TransactionJpaRepository jpaRepository;

    @Override
    public Transaction save(Transaction transaction) {
        return jpaRepository.save(transaction);
    }

    @Override
    public Optional<Transaction> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Transaction> findPendingChargeByPaymentId(Long paymentId) {
        return jpaRepository.findFirstByPaymentIdAndTypeAndStatus(paymentId, TransactionType.CHARGE, TransactionStatus.PENDING);
    }

    @Override
    public Optional<Transaction> findByProviderTransactionId(String providerTransactionId) {
        return jpaRepository.findByProviderTransactionId(providerTransactionId);
    }
}
