package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;

import java.util.Optional;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findById(Long id);

    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
