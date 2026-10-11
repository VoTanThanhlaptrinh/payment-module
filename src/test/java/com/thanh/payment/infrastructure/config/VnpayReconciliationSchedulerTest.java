package com.thanh.payment.infrastructure.config;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.service.VnpayReconciliationService;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.*;

class VnpayReconciliationSchedulerTest {

    @Test
    void shouldProcessDueTransactionsUsingConfiguredBatchSize() {
        TransactionRepository repository = mock(TransactionRepository.class);
        VnpayReconciliationService service = mock(VnpayReconciliationService.class);
        VnpayProperties properties = new VnpayProperties();
        properties.setReconciliationBatchSize(50);
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);
        Transaction first = transaction(1L);
        Transaction second = transaction(2L);
        when(repository.findReconciliationCandidates(OffsetDateTime.now(clock), 50))
                .thenReturn(List.of(first, second));
        VnpayReconciliationScheduler scheduler = new VnpayReconciliationScheduler(
                repository, service, properties, clock);

        scheduler.reconcileDueTransactions();

        verify(service).reconcile(first);
        verify(service).reconcile(second);
    }

    @Test
    void shouldContinueWithNextTransactionWhenOneFails() {
        TransactionRepository repository = mock(TransactionRepository.class);
        VnpayReconciliationService service = mock(VnpayReconciliationService.class);
        VnpayProperties properties = new VnpayProperties();
        properties.setReconciliationBatchSize(50);
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);
        Transaction broken = transaction(1L);
        Transaction healthy = transaction(2L);
        when(repository.findReconciliationCandidates(OffsetDateTime.now(clock), 50))
                .thenReturn(List.of(broken, healthy));
        when(service.reconcile(broken)).thenThrow(new IllegalStateException("db down"));
        VnpayReconciliationScheduler scheduler = new VnpayReconciliationScheduler(
                repository, service, properties, clock);

        scheduler.reconcileDueTransactions();

        verify(service).reconcile(healthy);
    }

    private Transaction transaction(Long id) {
        return new Transaction(id, 10L, Money.vnd(5_000), TransactionType.CHARGE,
                OffsetDateTime.parse("2026-10-04T09:00:00Z"));
    }
}
