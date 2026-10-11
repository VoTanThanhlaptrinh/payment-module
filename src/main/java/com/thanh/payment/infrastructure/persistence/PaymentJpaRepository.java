package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    // Spring Data tự sinh: select ... where order_id=? and status in (...) fetch first 1 row.
    Optional<Payment> findFirstByOrderIdAndStatusIn(Long orderId, Collection<PaymentStatus> statuses);
}
