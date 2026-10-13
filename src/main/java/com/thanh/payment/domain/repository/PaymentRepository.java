package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    // Spring Data tự sinh: select ... where order_id=? and status in (...) fetch first 1 row.
    Optional<Payment> findFirstByOrderIdAndStatusIn(Long orderId, Collection<PaymentStatus> statuses);

    // Tìm payment "đang hoạt động" của một order để từ chối tạo payment thứ hai.
    // Active = PENDING hoặc REVIEW_REQUIRED, khớp đúng điều kiện của unique index một phần uq_payments_order_id_active trong DB.
    default Optional<Payment> findActiveByOrderId(Long orderId) {
        return findFirstByOrderIdAndStatusIn(orderId, List.of(PaymentStatus.PENDING, PaymentStatus.REVIEW_REQUIRED));
    }
}
