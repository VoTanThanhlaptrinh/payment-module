package com.thanh.payment.application;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.api.dto.PaymentResponse;
import com.thanh.payment.application.provider.PaymentVerificationResult;
import com.thanh.payment.application.provider.WebhookVerifier;
import com.thanh.payment.shared.exception.ApiException;
import com.thanh.payment.application.provider.PaymentProvider;
import com.thanh.payment.domain.exception.ActivePaymentExistsException;
import com.thanh.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC);

    @Mock PaymentRepository paymentRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PaymentCreator paymentCreator;
    @Mock PaymentProviderFactory paymentProviderFactory;
    @Mock WebhookVerifierFactory webhookVerifierFactory;
    @Mock PaymentProvider provider;
    @Mock IdempotencyStore store;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, transactionRepository, idempotencyGuard,
                paymentCreator, paymentProviderFactory, webhookVerifierFactory, CLOCK);
    }

    @Test
    void shouldCreatePaymentAndMarkIdempotencyKeyCompleted() {
        PaymentResponse created = response(1L);
        givenAcquired();
        when(paymentCreator.create(any(), eq(provider), anyString(), anyString(), eq(OffsetDateTime.now(CLOCK))))
                .thenReturn(created);

        PaymentResponse response = service.createPayment(command("idem-key-0001"));

        assertSame(created, response);
        verify(store).complete(anyString(), eq("token-1"), eq(1L));
        verify(store, never()).release(anyString(), anyString());
    }

    @Test
    void shouldReplayWithoutCreatingWhenRedisSaysAlreadyCompleted() {
        when(paymentProviderFactory.getEnabledProvider(PaymentMethodType.VNPAY)).thenReturn(provider);
        when(idempotencyGuard.acquire(anyString(), anyString()))
                .thenReturn(new IdempotencyGuard.AlreadyCompleted(7L));
        PaymentResponse replayed = response(7L);
        when(paymentCreator.replay(7L)).thenReturn(replayed);

        assertSame(replayed, service.createPayment(command("idem-key-0001")));

        verify(paymentCreator, never()).create(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void shouldReleaseKeyWhenCreationFails() {
        givenAcquired();
        when(paymentCreator.create(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.createPayment(command("idem-key-0002")));

        verify(store).release(anyString(), eq("token-1"));
        verify(store, never()).complete(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldReplayFromDatabaseWhenIdempotencyUniqueConstraintIsViolated() {
        givenAcquired();
        when(paymentCreator.create(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateIdempotencyKeyException(new RuntimeException("duplicate")));
        PaymentResponse replayed = response(9L);
        when(paymentCreator.replayOrRestart(any(), any(), anyString(), anyString(), any())).thenReturn(replayed);

        PaymentResponse response = service.createPayment(command("idem-key-0003"));

        assertSame(replayed, response);
        verify(store).complete(anyString(), eq("token-1"), eq(9L));
    }

    @Test
    void shouldRejectOrderThatAlreadyHasActivePaymentWhenUniqueConstraintIsViolated() {
        givenAcquired();
        when(paymentCreator.create(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new ActivePaymentExistsException(new RuntimeException("active")));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createPayment(command("idem-key-0004")));

        assertEquals("PAYMENT_ALREADY_PENDING", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(store).release(anyString(), eq("token-1"));
    }

    @Test
    void shouldReportActivePaymentWhenRestartOfExpiredKeyHitsOrderConstraint() {
        givenAcquired();
        when(paymentCreator.create(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateIdempotencyKeyException(new RuntimeException("duplicate")));
        when(paymentCreator.replayOrRestart(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new ActivePaymentExistsException(new RuntimeException("active")));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createPayment(command("idem-key-0008")));

        assertEquals("PAYMENT_ALREADY_PENDING", exception.getCode());
        verify(store).release(anyString(), eq("token-1"));
    }

    @Test
    void shouldRethrowUnknownIntegrityViolation() {
        givenAcquired();
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("null value in column");
        when(paymentCreator.create(any(), any(), anyString(), anyString(), any())).thenThrow(unknown);

        assertSame(unknown, assertThrows(DataIntegrityViolationException.class,
                () -> service.createPayment(command("idem-key-0005"))));
        verify(store).release(anyString(), eq("token-1"));
    }

    @Test
    void shouldPropagateGuardRejectionWithoutTouchingDatabase() {
        when(paymentProviderFactory.getEnabledProvider(PaymentMethodType.VNPAY)).thenReturn(provider);
        when(idempotencyGuard.acquire(anyString(), anyString())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "in progress", 1));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createPayment(command("idem-key-0006")));

        assertEquals("IDEMPOTENCY_IN_PROGRESS", exception.getCode());
        verifyNoInteractions(paymentCreator);
    }

    @Test
    void shouldAllowAmountBelowVnpayMinimumForPayos() {
        when(paymentProviderFactory.getEnabledProvider(PaymentMethodType.PAYOS)).thenReturn(provider);
        PaymentResponse created = response(9L);
        when(idempotencyGuard.acquire(anyString(), anyString()))
                .thenReturn(new IdempotencyGuard.Acquired(new IdempotencyTicket(store, "k", "token-1")));
        when(paymentCreator.create(any(), eq(provider), anyString(), anyString(), any())).thenReturn(created);
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(101L).amount(BigDecimal.valueOf(2_000)).currency(Currency.VND)
                .method(PaymentMethodType.PAYOS).idempotencyKey("idem-key-0008").clientIp("127.0.0.1")
                .build();

        assertSame(created, service.createPayment(command));
    }

    @Test
    void shouldRejectInvalidAmountBeforeAcquiringKey() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(101L).amount(BigDecimal.valueOf(4_999)).currency(Currency.VND)
                .method(PaymentMethodType.VNPAY).idempotencyKey("idem-key-0007").clientIp("127.0.0.1")
                .build();

        ApiException exception = assertThrows(ApiException.class, () -> service.createPayment(command));

        assertEquals("INVALID_AMOUNT", exception.getCode());
        verifyNoInteractions(idempotencyGuard, paymentCreator);
    }

    // Guard giả trả về một ticket thật gắn với store giả, để kiểm tra complete/release được gọi đúng.
    @Test
    void verifyWebhookShouldAnswerNotFoundWhenProviderIsDisabled() {
        when(paymentProviderFactory.getProvider(PaymentMethodType.MOMO)).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.verifyWebhook(PaymentMethodType.MOMO, new Object()));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PROVIDER_DISABLED", exception.getCode());
        verifyNoInteractions(webhookVerifierFactory);
    }

    @Test
    void verifyWebhookShouldAnswerUnauthorizedWhenSignatureIsInvalid() {
        WebhookVerifier verifier = org.mockito.Mockito.mock(WebhookVerifier.class);
        Object payload = new Object();
        when(paymentProviderFactory.getProvider(PaymentMethodType.MOMO)).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(true);
        when(webhookVerifierFactory.getVerifier(PaymentMethodType.MOMO)).thenReturn(verifier);
        when(verifier.verify(payload)).thenReturn(PaymentVerificationResult.builder().isSignatureValid(false).build());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.verifyWebhook(PaymentMethodType.MOMO, payload));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("INVALID_SIGNATURE", exception.getCode());
        verifyNoInteractions(paymentRepository, transactionRepository);
    }

    private void givenAcquired() {
        when(paymentProviderFactory.getEnabledProvider(PaymentMethodType.VNPAY)).thenReturn(provider);
        when(idempotencyGuard.acquire(anyString(), anyString()))
                .thenReturn(new IdempotencyGuard.Acquired(new IdempotencyTicket(store, "hash", "token-1")));
    }

    private PaymentResponse response(long paymentId) {
        return PaymentResponse.builder().id(paymentId).paymentId(paymentId).build();
    }

    private CreatePaymentCommand command(String key) {
        return CreatePaymentCommand.builder()
                .orderId(101L)
                .amount(BigDecimal.valueOf(5_000))
                .currency(Currency.VND)
                .method(PaymentMethodType.VNPAY)
                .idempotencyKey(key)
                .clientIp("127.0.0.1")
                .build();
    }
}
