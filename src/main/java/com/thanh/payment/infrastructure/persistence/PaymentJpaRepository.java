package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
