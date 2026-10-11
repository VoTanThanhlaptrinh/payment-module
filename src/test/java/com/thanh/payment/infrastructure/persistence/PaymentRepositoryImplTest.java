package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.exception.ActivePaymentExistsException;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Money;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRepositoryImplTest {

    @Mock PaymentJpaRepository jpaRepository;
    private PaymentRepositoryImpl repository;
    private final Payment payment = new Payment(1L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);

    @BeforeEach
    void setUp() {
        repository = new PaymentRepositoryImpl(jpaRepository);
    }

    @Test
    void shouldTranslateActivePaymentUniqueViolation() {
        when(jpaRepository.saveAndFlush(any())).thenThrow(violation("uq_payments_order_id_active"));

        assertThrows(ActivePaymentExistsException.class, () -> repository.saveAndFlush(payment));
    }

    @Test
    void shouldRethrowOtherConstraintViolationsUnchanged() {
        DataIntegrityViolationException other = violation("fk_transactions_payment");
        when(jpaRepository.saveAndFlush(any())).thenThrow(other);

        assertSame(other, assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(payment)));
    }

    static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraintName));
    }
}
