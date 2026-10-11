package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.exception.ActivePaymentExistsException;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public Payment save(Payment payment) {
        return jpaRepository.save(payment);
    }

    // saveAndFlush: INSERT/UPDATE chạy ngay chứ không đợi cuối transaction.
    @Override
    public Payment saveAndFlush(Payment payment) {
        try {
            return jpaRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException exception) {
            // uq_payments_order_id_active: order đã có payment PENDING/REVIEW_REQUIRED.
            if (ConstraintViolations.violates(exception, "uq_payments_order_id_active")) {
                throw new ActivePaymentExistsException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Payment> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status) {
        return jpaRepository.findByOrderIdAndStatus(orderId, status);
    }

    // Active = PENDING hoặc REVIEW_REQUIRED. Khớp đúng điều kiện của unique index một phần uq_payments_order_id_active trong DB.
    @Override
    public Optional<Payment> findActiveByOrderId(Long orderId) {
        return jpaRepository.findFirstByOrderIdAndStatusIn(
                orderId, java.util.List.of(PaymentStatus.PENDING, PaymentStatus.REVIEW_REQUIRED));
    }
}
