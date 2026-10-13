package com.thanh.payment.application;

import com.thanh.payment.api.dto.webhook.VnpayIpnData;
import com.thanh.payment.api.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.model.TransactionType;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VnpayIpnProcessorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC);

    @Mock TransactionRepository transactionRepository;
    @Mock PaymentRepository paymentRepository;
    private VnpayIpnProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new VnpayIpnProcessor(transactionRepository, paymentRepository, CLOCK);
    }

    @Test
    void shouldCompletePaymentAndTransaction() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("00", "00", "500000"));

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        assertEquals("98765", transaction.getProviderTransactionId());
        verify(paymentRepository).save(payment);
        verify(transactionRepository).save(transaction);
    }

    @Test
    void shouldRecordPaymentDetailsAndClockTimeOnSuccess() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("vnp_PayDate", "20261011100000");
        extra.put("vnp_BankCode", "NCB");
        extra.put("vnp_CardType", "ATM");
        extra.put("vnp_BankTranNo", "VNP14012345");

        processor.processVerified(data("00", "00", "500000", extra));

        assertEquals(OffsetDateTime.parse("2026-10-11T03:00:00Z").toInstant(), transaction.getPaidAt().toInstant());
        assertEquals(OffsetDateTime.parse("2026-10-11T03:00:00Z"), transaction.getCompletedAt());
        assertEquals("NCB", transaction.getProviderBankCode());
        assertEquals("ATM", transaction.getProviderCardType());
        assertEquals("VNP14012345", transaction.getProviderBankTranNo());
    }

    @Test
    void shouldStillCompleteWhenPayDateIsMalformed() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(
                data("00", "00", "500000", Map.of("vnp_PayDate", "not-a-date")));

        assertEquals("00", response.rspCode());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        assertNull(transaction.getPaidAt());
    }

    @Test
    void shouldRequireManualReviewForSuspiciousCode() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("07", "07", "500000"));

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.REVIEW_REQUIRED, payment.getStatus());
        assertEquals(TransactionStatus.REVIEW_REQUIRED, transaction.getStatus());
    }

    @Test
    void shouldRequireManualReviewForSuspiciousResponseWithSuccessfulTransactionStatus() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("07", "00", "500000"));

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.REVIEW_REQUIRED, payment.getStatus());
        assertEquals(TransactionStatus.REVIEW_REQUIRED, transaction.getStatus());
    }

    @Test
    void shouldRejectAmountMismatchWithoutMutation() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("00", "00", "500100"));

        assertEquals("04", response.rspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldReturnAlreadyConfirmedForDuplicateCallback() {
        Payment payment = payment();
        Transaction transaction = transaction();
        payment.markCompleted();
        transaction.markSuccess("98765", "00");
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("00", "00", "500000"));

        assertEquals("02", response.rspCode());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldAcknowledgeLateSuccessOnExpiredTransactionWithoutChangingState() {
        Payment payment = payment();
        Transaction transaction = transaction();
        payment.markExpired();
        transaction.markExpired(null, "91", "expired");
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("00", "00", "500000"));

        // Vẫn trả 02 và KHÔNG đổi trạng thái (chỉ log ERROR để người vận hành xử lý tay).
        assertEquals("02", response.rspCode());
        assertEquals(PaymentStatus.EXPIRED, payment.getStatus());
        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
        verify(paymentRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void shouldExpireTimedOutPayment() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("11", "11", "500000"));

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.EXPIRED, payment.getStatus());
        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
        assertEquals("11", transaction.getProviderResponseCode());
    }

    @Test
    void shouldPersistDeclineCodeAndProviderReference() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("24", "24", "500000"));

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(TransactionStatus.FAILED, transaction.getStatus());
        assertEquals("24", transaction.getProviderResponseCode());
        assertEquals("98765", transaction.getProviderTransactionId());
    }

    @Test
    void shouldLeaveStatePendingForUnknownCode() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("88", "88", "500000"));

        assertEquals("99", response.rspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldRejectConflictingSuccessCodesWithoutMutation() {
        Payment payment = payment();
        Transaction transaction = transaction();
        mockEntities(payment, transaction);

        VnpayIpnResponse response = processor.processVerified(data("00", "24", "500000"));

        assertEquals("99", response.rspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    private void mockEntities(Payment payment, Transaction transaction) {
        when(transactionRepository.findByIdForUpdate(12345L)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
    }

    private Payment payment() {
        return new Payment(1L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);
    }

    private Transaction transaction() {
        return new Transaction(12345L, 1L, Money.vnd(5_000),
                TransactionType.CHARGE, OffsetDateTime.now().plusMinutes(15));
    }

    private VnpayIpnData data(String responseCode, String status, String amount) {
        return data(responseCode, status, amount, Map.of());
    }

    private VnpayIpnData data(String responseCode, String status, String amount, Map<String, String> extra) {
        Map<String, String> parameters = new LinkedHashMap<>(extra);
        parameters.put("vnp_TxnRef", "12345");
        parameters.put("vnp_Amount", amount);
        parameters.put("vnp_ResponseCode", responseCode);
        parameters.put("vnp_TransactionStatus", status);
        parameters.put("vnp_TransactionNo", "98765");
        return new VnpayIpnData(true, false, parameters);
    }
}
