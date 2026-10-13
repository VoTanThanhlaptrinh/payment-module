package com.thanh.payment.application;

import com.thanh.payment.infrastructure.provider.vnpay.VnpayProperties;
import com.thanh.payment.application.provider.VnpayQueryDecision;
import com.thanh.payment.application.provider.VnpayQueryResult;
import com.thanh.payment.domain.enums.PaymentMethodType;
import com.thanh.payment.domain.enums.PaymentStatus;
import com.thanh.payment.domain.enums.TransactionStatus;
import com.thanh.payment.domain.enums.TransactionType;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VnpayReconciliationProcessorTest {

    @Mock TransactionRepository transactionRepository;
    @Mock PaymentRepository paymentRepository;
    private VnpayReconciliationProcessor processor;

    @BeforeEach
    void setUp() {
        VnpayProperties properties = new VnpayProperties();
        properties.setReconciliationMaxAttempts(2);
        properties.setReconciliationInterval(java.time.Duration.ofMinutes(5));
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);
        processor = new VnpayReconciliationProcessor(
                transactionRepository, paymentRepository, properties, clock);
    }

    @Test
    void shouldCompletePendingPaymentFromVerifiedQueryResult() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayReconciliationOutcome outcome = processor.apply(12345L, new VnpayQueryResult(
                VnpayQueryDecision.SUCCESS, "TXN_00", "00", "98765", "Success"));

        assertEquals(VnpayReconciliationOutcome.UPDATED, outcome);
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        assertEquals(1, transaction.getReconciliationAttempts());
        verify(paymentRepository).save(payment);
        verify(transactionRepository).save(transaction);
    }

    @Test
    void shouldRetryThenExhaustWithoutFailingPayment() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);
        VnpayQueryResult retry = VnpayQueryResult.retry("TRANSPORT_ERROR", "timeout");

        assertEquals(VnpayReconciliationOutcome.RETRY_SCHEDULED, processor.apply(12345L, retry));
        assertEquals(OffsetDateTime.parse("2026-10-04T10:05:00Z"), transaction.getNextReconciliationAt());
        assertEquals(VnpayReconciliationOutcome.EXHAUSTED, processor.apply(12345L, retry));

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        assertEquals(2, transaction.getReconciliationAttempts());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldExpireWhenVnpayHasNoRecordAfterExpiryPlusGrace() {
        Payment payment = payment();
        // hạn 09:45, grace mặc định 5 phút, bây giờ 10:00 => đã quá grace
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayReconciliationOutcome outcome = processor.apply(12345L, new VnpayQueryResult(
                VnpayQueryDecision.NOT_FOUND, "API_91", null, null, "Not found"));

        assertEquals(VnpayReconciliationOutcome.UPDATED, outcome);
        assertEquals(PaymentStatus.EXPIRED, payment.getStatus());
        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
        assertEquals("91", transaction.getProviderResponseCode());
        verify(paymentRepository).save(payment);
    }

    @Test
    void shouldKeepPendingWhenVnpayHasNoRecordButGraceHasNotPassed() {
        Payment payment = payment();
        // hạn 09:58, bây giờ 10:00 => chưa qua grace 5 phút
        Transaction transaction = new Transaction(12345L, 1L, Money.vnd(5_000), TransactionType.CHARGE,
                OffsetDateTime.parse("2026-10-04T09:58:00Z"));
        mockEntities(payment, transaction);

        VnpayReconciliationOutcome outcome = processor.apply(12345L, new VnpayQueryResult(
                VnpayQueryDecision.NOT_FOUND, "API_91", null, null, "Not found"));

        assertEquals(VnpayReconciliationOutcome.RETRY_SCHEDULED, outcome);
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        assertEquals(1, transaction.getReconciliationAttempts());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldRecordPaymentDetailsFromQueryResult() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        processor.apply(12345L, new VnpayQueryResult(VnpayQueryDecision.SUCCESS, "TXN_00", "00", "98765",
                "Success", "20261004165500", "NCB"));

        assertEquals(OffsetDateTime.parse("2026-10-04T09:55:00Z").toInstant(), transaction.getPaidAt().toInstant());
        assertEquals("NCB", transaction.getProviderBankCode());
        assertEquals(OffsetDateTime.parse("2026-10-04T10:00:00Z"), transaction.getCompletedAt());
    }

    @Test
    void shouldBlockReconciliationWhenPaymentIsNoLongerPending() {
        Payment payment = payment();
        payment.markFailed();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayReconciliationOutcome outcome = processor.apply(12345L, VnpayQueryResult.retry("TXN_01", "pending"));

        assertEquals(VnpayReconciliationOutcome.INVALID_LOCAL_STATE, outcome);
        assertTrue(transaction.isReconciliationExhausted());
        assertNull(transaction.getNextReconciliationAt());
        assertEquals("INVALID_LOCAL_STATE", transaction.getLastReconciliationCode());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        verify(transactionRepository).save(transaction);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void mustNotOverwriteTerminalTransaction() {
        Payment payment = payment();
        Transaction transaction = transaction();
        payment.markCompleted();
        transaction.markSuccess("98765", "00");
        when(transactionRepository.findByIdForUpdate(12345L)).thenReturn(Optional.of(transaction));

        VnpayReconciliationOutcome outcome = processor.apply(12345L, new VnpayQueryResult(
                VnpayQueryDecision.FAILED, "TXN_02", "02", "98765", "Failed"));

        assertEquals(VnpayReconciliationOutcome.ALREADY_TERMINAL, outcome);
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        verify(transactionRepository, never()).save(any());
    }

    private void mockEntities(Payment payment, Transaction transaction) {
        when(transactionRepository.findByIdForUpdate(12345L)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
    }

    private Payment payment() {
        return new Payment(1L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);
    }

    private Transaction transaction() {
        return new Transaction(12345L, 1L, Money.vnd(5_000), TransactionType.CHARGE,
                OffsetDateTime.parse("2026-10-04T09:45:00Z"));
    }
}
