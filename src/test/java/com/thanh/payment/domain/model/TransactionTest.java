package com.thanh.payment.domain.model;

import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    @Test
    @DisplayName("Khởi tạo Transaction CHARGE với trạng thái ban đầu PENDING")
    void shouldCreateChargeTransaction() {
        OffsetDateTime timeout = OffsetDateTime.now().plusMinutes(15);
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), timeout);

        assertNotNull(tx);
        assertEquals(2001L, tx.getPaymentId());
        assertEquals(Money.vnd(150000), tx.getAmount());
        assertEquals(TransactionType.CHARGE, tx.getType());
        assertEquals(TransactionStatus.PENDING, tx.getStatus());
        assertEquals(timeout, tx.getExpiredAt());
        assertEquals(timeout, tx.getNextReconciliationAt());
        assertEquals(0, tx.getReconciliationAttempts());
        assertFalse(tx.isReconciliationExhausted());
        assertNull(tx.getProviderTransactionId());
        assertNull(tx.getCompletedAt());
        assertTrue(tx.isPending());
    }

    @Test
    @DisplayName("Đánh dấu Transaction thành công khi nhận callback IPN")
    void shouldMarkTransactionAsSuccess() {
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), OffsetDateTime.now().plusMinutes(15));
        tx.markSuccess("VNPAY_TX_987654");

        assertEquals(TransactionStatus.SUCCESS, tx.getStatus());
        assertEquals("VNPAY_TX_987654", tx.getProviderTransactionId());
        assertNotNull(tx.getCompletedAt());
        assertTrue(tx.isSuccess());
    }

    @Test
    @DisplayName("Đánh dấu Transaction thất bại kèm lý do")
    void shouldMarkTransactionAsFailed() {
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), OffsetDateTime.now().plusMinutes(15));
        tx.markFailed("Insufficient funds in account");

        assertEquals(TransactionStatus.FAILED, tx.getStatus());
        assertEquals("Insufficient funds in account", tx.getFailureReason());
        assertNotNull(tx.getCompletedAt());
        assertTrue(tx.isFailed());
    }

    @Test
    @DisplayName("Đánh dấu Transaction hết hạn timeout")
    void shouldMarkTransactionAsExpired() {
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), OffsetDateTime.now().minusMinutes(5));
        tx.markExpired();

        assertEquals(TransactionStatus.EXPIRED, tx.getStatus());
        assertTrue(tx.isExpired());
    }

    @Test
    @DisplayName("Ném lỗi khi đánh dấu thất bại một giao dịch đã thành công")
    void shouldThrowExceptionWhenFailingSuccessfulTransaction() {
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), OffsetDateTime.now().plusMinutes(15));
        tx.markSuccess("TX_123");

        assertThrows(IllegalStateException.class, () -> tx.markFailed("Error"));
    }

    @Test
    void shouldScheduleAndExhaustReconciliationAttempts() {
        OffsetDateTime attemptedAt = OffsetDateTime.parse("2026-10-04T10:00:00Z");
        Transaction tx = Transaction.createCharge(2001L, Money.vnd(150000), attemptedAt.minusMinutes(1));

        tx.recordReconciliationAttempt(attemptedAt, "TRANSPORT_ERROR", attemptedAt.plusMinutes(5), 2);

        assertEquals(1, tx.getReconciliationAttempts());
        assertEquals(attemptedAt.plusMinutes(5), tx.getNextReconciliationAt());
        assertFalse(tx.isReconciliationExhausted());

        tx.recordReconciliationAttempt(attemptedAt.plusMinutes(5), "API_91", attemptedAt.plusMinutes(10), 2);

        assertEquals(2, tx.getReconciliationAttempts());
        assertNull(tx.getNextReconciliationAt());
        assertTrue(tx.isReconciliationExhausted());
        assertEquals("API_91", tx.getLastReconciliationCode());
    }
}
