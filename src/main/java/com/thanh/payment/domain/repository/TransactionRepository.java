package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Transaction;

import java.util.Optional;

public interface TransactionRepository {
    Transaction save(Transaction transaction);

    Optional<Transaction> findById(Long id);

    Optional<Transaction> findPendingChargeByPaymentId(Long paymentId);

    Optional<Transaction> findByProviderTransactionId(String providerTransactionId);
}
