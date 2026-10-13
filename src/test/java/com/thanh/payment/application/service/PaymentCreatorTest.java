package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.application.port.out.ProviderCheckoutStatus;
import com.thanh.payment.domain.model.IdempotencyKey;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.IdempotencyKeyRepository;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Currency;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCreatorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-04T10:00:00+07:00");

    @Mock PaymentRepository paymentRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock PaymentProvider provider;

    private PaymentCreator creator;

    @BeforeEach
    void setUp() {
        creator = new PaymentCreator(paymentRepository, transactionRepository, idempotencyKeyRepository);
    }

    @Test
    void shouldCreateChargeBeforeGeneratingCheckoutAndStoreCompletedKey() {
        givenPersistedPaymentAndTransaction();

        PaymentResponse response = creator.create(command(5_000), provider, "key-hash", "req-hash", NOW);

        assertEquals(1L, response.getPaymentId());
        assertEquals("55", response.getTransactionId());
        assertNotNull(response.getCheckoutUrl());
        ArgumentCaptor<ProviderCheckoutRequest> request = ArgumentCaptor.forClass(ProviderCheckoutRequest.class);
        verify(provider).processPayment(request.capture());
        assertEquals(55L, request.getValue().getTransactionId());
        // Không SELECT trước: chống trùng dựa vào unique. Key được ghi TRƯỚC payment (để request lặp lại nhận kết quả cũ
        // thay vì đụng unique của order), rồi cập nhật COMPLETED ở cuối.
        ArgumentCaptor<IdempotencyKey> key = ArgumentCaptor.forClass(IdempotencyKey.class);
        InOrder order = inOrder(idempotencyKeyRepository, paymentRepository);
        order.verify(idempotencyKeyRepository).saveAndFlush(key.capture());
        order.verify(paymentRepository).saveAndFlush(any(Payment.class));
        order.verify(idempotencyKeyRepository).save(key.getValue());
        assertEquals(1L, key.getValue().getPaymentId());
        verify(idempotencyKeyRepository, never()).find(any(), any());
        verify(paymentRepository, never()).findActiveByOrderId(any());
    }

    @Test
    void shouldReplayStoredPaymentWhenKeyExistsWithSameRequest() {
        IdempotencyKey key = IdempotencyKey.start("key-hash", "req-hash", NOW.plusHours(1));
        key.complete(7L);
        when(idempotencyKeyRepository.find(IdempotencyKey.CREATE_PAYMENT_SCOPE, "key-hash")).thenReturn(Optional.of(key));
        Payment payment = new Payment(7L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);
        Transaction transaction = new Transaction(70L, 7L, Money.vnd(5_000), TransactionType.CHARGE, NOW.plusMinutes(15));
        transaction.attachCheckout("https://sandbox.vnpayment.vn/same-url", NOW.plusMinutes(15));
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        when(transactionRepository.findLatestChargeByPaymentId(7L)).thenReturn(Optional.of(transaction));

        PaymentResponse response = creator.replayOrRestart(command(5_000), provider, "key-hash", "req-hash", NOW);

        assertEquals("https://sandbox.vnpayment.vn/same-url", response.getCheckoutUrl());
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(provider, never()).processPayment(any());
    }

    @Test
    void shouldRejectStoredKeyUsedWithDifferentRequest() {
        IdempotencyKey key = IdempotencyKey.start("key-hash", "other-hash", NOW.plusHours(1));
        key.complete(7L);
        when(idempotencyKeyRepository.find(IdempotencyKey.CREATE_PAYMENT_SCOPE, "key-hash")).thenReturn(Optional.of(key));

        ApiException exception = assertThrows(ApiException.class,
                () -> creator.replayOrRestart(command(5_000), provider, "key-hash", "req-hash", NOW));

        assertEquals("IDEMPOTENCY_CONFLICT", exception.getCode());
    }

    @Test
    void shouldRestartExpiredKeyAndCreateNewPayment() {
        IdempotencyKey expired = IdempotencyKey.start("key-hash", "old-hash", NOW.minusHours(1));
        expired.complete(3L);
        when(idempotencyKeyRepository.find(IdempotencyKey.CREATE_PAYMENT_SCOPE, "key-hash")).thenReturn(Optional.of(expired));
        givenPersistedPaymentAndTransaction();

        PaymentResponse response = creator.replayOrRestart(command(5_000), provider, "key-hash", "req-hash", NOW);

        assertEquals(1L, response.getPaymentId());
        assertEquals("req-hash", expired.getRequestHash());
        assertEquals(1L, expired.getPaymentId());
        verify(idempotencyKeyRepository).saveAndFlush(expired);
        verify(idempotencyKeyRepository).save(expired);
    }

    @Test
    void shouldReplayByPaymentIdWithoutWriting() {
        Payment payment = new Payment(7L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);
        Transaction transaction = new Transaction(70L, 7L, Money.vnd(5_000), TransactionType.CHARGE, NOW.plusMinutes(15));
        transaction.attachCheckout("https://sandbox.vnpayment.vn/same-url", NOW.plusMinutes(15));
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        when(transactionRepository.findLatestChargeByPaymentId(7L)).thenReturn(Optional.of(transaction));

        PaymentResponse response = creator.replay(7L);

        assertEquals(7L, response.getPaymentId());
        assertEquals("https://sandbox.vnpayment.vn/same-url", response.getCheckoutUrl());
        verify(idempotencyKeyRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldStoreProviderOrderCodeAndUseProviderExpiry() {
        Payment payment = new Payment(1L, 101L, Money.vnd(50_000), PaymentMethodType.PAYOS);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenReturn(payment);
        Transaction transaction = new Transaction(55L, 1L, Money.vnd(50_000), TransactionType.CHARGE, NOW.plusMinutes(30));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(transaction);
        when(provider.getExpiryMinutes()).thenReturn(30);
        when(provider.processPayment(any())).thenReturn(ProviderCheckoutResult.builder()
                .transactionId("55")
                .checkoutUrl("https://pay.payos.vn/web/abc")
                .expiresAt(NOW.plusMinutes(30))
                .status(ProviderCheckoutStatus.PENDING)
                .providerOrderCode(1700000000123L)
                .build());

        creator.create(command(50_000), provider, "key-hash", "req-hash", NOW);

        assertEquals(1700000000123L, transaction.getProviderOrderCode());
        ArgumentCaptor<ProviderCheckoutRequest> request = ArgumentCaptor.forClass(ProviderCheckoutRequest.class);
        verify(provider).processPayment(request.capture());
        assertEquals(NOW.plusMinutes(30), request.getValue().getExpiresAt());
    }

    private void givenPersistedPaymentAndTransaction() {
        when(provider.getExpiryMinutes()).thenReturn(15);
        Payment payment = new Payment(1L, 101L, Money.vnd(5_000), PaymentMethodType.VNPAY);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenReturn(payment);
        Transaction transaction = new Transaction(55L, 1L, Money.vnd(5_000), TransactionType.CHARGE, NOW.plusMinutes(15));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenReturn(transaction);
        when(provider.processPayment(any())).thenReturn(ProviderCheckoutResult.builder()
                .transactionId("55")
                .checkoutUrl("https://sandbox.vnpayment.vn/checkout?signed=true")
                .expiresAt(NOW.plusMinutes(15))
                .status(ProviderCheckoutStatus.PENDING)
                .build());
    }

    private CreatePaymentCommand command(long amount) {
        return CreatePaymentCommand.builder()
                .orderId(101L).amount(BigDecimal.valueOf(amount)).currency(Currency.VND)
                .method(PaymentMethodType.VNPAY).idempotencyKey("idem-key-0001").clientIp("127.0.0.1")
                .build();
    }
}
