package com.thanh.payment.domain.model;

import com.thanh.payment.domain.valueobject.Currency;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

    @Test
    @DisplayName("Khởi tạo Payment với trạng thái ban đầu PENDING")
    void shouldCreatePaymentInPendingStatus() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.VNPAY);

        assertNotNull(payment);
        assertEquals(1001L, payment.getOrderId());
        assertEquals(Money.vnd(500000), payment.getTotalAmount());
        assertEquals(PaymentMethodType.VNPAY, payment.getMethod());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertTrue(payment.isPending());
    }

    @Test
    @DisplayName("Chuyển trạng thái Payment từ PENDING sang COMPLETED")
    void shouldCompletePayment() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.MOMO);
        payment.markCompleted();

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertTrue(payment.isCompleted());
    }

    @Test
    @DisplayName("Chuyển trạng thái Payment từ PENDING sang FAILED")
    void shouldFailPayment() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.CREDIT_CARD);
        payment.markFailed();

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertTrue(payment.isFailed());
    }

    @Test
    @DisplayName("Ném lỗi khi chuyển trạng thái không hợp lệ: Fail một Payment đã hoàn tất")
    void shouldThrowExceptionWhenFailingCompletedPayment() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.BANK_TRANSFER);
        payment.markCompleted();

        assertThrows(IllegalStateException.class, payment::markFailed);
    }

    @Test
    @DisplayName("Hoàn tiền thành công từ trạng thái COMPLETED")
    void shouldRefundCompletedPayment() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.VNPAY);
        payment.markCompleted();

        payment.markRefunded();
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertTrue(payment.isRefunded());
    }

    @Test
    @DisplayName("Ném lỗi khi hoàn tiền từ trạng thái PENDING")
    void shouldThrowExceptionWhenRefundingPendingPayment() {
        Payment payment = Payment.create(1001L, Money.vnd(500000), PaymentMethodType.COD);

        assertThrows(IllegalStateException.class, payment::markRefunded);
    }
}
