package com.thanh.payment.domain.repository;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;

import java.util.Optional;

public interface PaymentRepository {
    Payment save(Payment payment);

    // Lưu rồi flush ngay xuống DB để vi phạm ràng buộc (vd unique 1 payment active/order) lộ ra tại chỗ và id TSID có sẵn.
    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(Long id);

    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    // Tìm payment "đang hoạt động" (PENDING hoặc REVIEW_REQUIRED) của một order. Dùng để từ chối tạo payment thứ hai.
    Optional<Payment> findActiveByOrderId(Long orderId);
}
