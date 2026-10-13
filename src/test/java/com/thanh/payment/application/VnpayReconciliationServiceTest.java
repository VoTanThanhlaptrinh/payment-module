package com.thanh.payment.application;

import com.thanh.payment.application.provider.VnpayQueryResult;
import com.thanh.payment.application.provider.VnpayTransactionQuery;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class VnpayReconciliationServiceTest {

    @Test
    void shouldTurnTransportFailureIntoRetryAttempt() {
        VnpayTransactionQuery query = mock(VnpayTransactionQuery.class);
        VnpayReconciliationProcessor processor = mock(VnpayReconciliationProcessor.class);
        VnpayReconciliationService service = new VnpayReconciliationService(query, processor);
        Transaction transaction = new Transaction(
                12345L, 1L, Money.vnd(5_000), TransactionType.CHARGE, OffsetDateTime.now());
        when(query.query(transaction)).thenThrow(new IllegalStateException("timeout"));
        when(processor.apply(eq(12345L), any())).thenReturn(VnpayReconciliationOutcome.RETRY_SCHEDULED);

        assertEquals(VnpayReconciliationOutcome.RETRY_SCHEDULED, service.reconcile(transaction));
        verify(processor).apply(eq(12345L), argThat(result ->
                result.equals(VnpayQueryResult.retry("TRANSPORT_ERROR", "VNPay QueryDR transport failure"))));
    }
}
