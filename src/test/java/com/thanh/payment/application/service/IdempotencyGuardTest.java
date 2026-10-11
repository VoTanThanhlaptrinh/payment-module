package com.thanh.payment.application.service;

import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.port.out.IdempotencyStore;
import com.thanh.payment.application.port.out.IdempotencyStore.Existing;
import com.thanh.payment.application.port.out.IdempotencyStore.IdempotencyStoreUnavailableException;
import com.thanh.payment.application.port.out.IdempotencyStore.Reserved;
import com.thanh.payment.application.port.out.IdempotencyStore.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock IdempotencyStore store;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(store);
    }

    @Test
    void shouldAcquireWhenKeyIsNew() {
        when(store.reserve("key", "req")).thenReturn(new Reserved("token"));

        assertInstanceOf(IdempotencyGuard.Acquired.class, guard.acquire("key", "req"));
    }

    @Test
    void shouldReturnAlreadyCompletedForSameRequest() {
        when(store.reserve("key", "req")).thenReturn(new Existing(State.COMPLETED, "req", 42L));

        IdempotencyGuard.Acquisition result = guard.acquire("key", "req");

        assertEquals(42L, assertInstanceOf(IdempotencyGuard.AlreadyCompleted.class, result).paymentId());
    }

    @Test
    void shouldRejectInProgressRequestWithRetryAfter() {
        when(store.reserve("key", "req")).thenReturn(new Existing(State.IN_PROGRESS, "req", null));

        ApiException exception = assertThrows(ApiException.class, () -> guard.acquire("key", "req"));

        assertEquals("IDEMPOTENCY_IN_PROGRESS", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(1, exception.getRetryAfterSeconds());
    }

    @Test
    void shouldRejectSameKeyWithDifferentRequest() {
        when(store.reserve("key", "req")).thenReturn(new Existing(State.COMPLETED, "other", 42L));

        ApiException exception = assertThrows(ApiException.class, () -> guard.acquire("key", "req"));

        assertEquals("IDEMPOTENCY_CONFLICT", exception.getCode());
    }

    @Test
    void shouldReturn503WhenStoreIsUnavailable() {
        when(store.reserve("key", "req")).thenThrow(new IdempotencyStoreUnavailableException("down", null));

        ApiException exception = assertThrows(ApiException.class, () -> guard.acquire("key", "req"));

        assertEquals("IDEMPOTENCY_STORE_UNAVAILABLE", exception.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals(5, exception.getRetryAfterSeconds());
    }

    @Test
    void closeShouldReleaseWhenNotCompleted() {
        IdempotencyTicket ticket = new IdempotencyTicket(store, "key", "token");

        ticket.close();

        verify(store).release("key", "token");
    }

    @Test
    void closeAfterCompleteShouldNotRelease() {
        IdempotencyTicket ticket = new IdempotencyTicket(store, "key", "token");

        ticket.complete(7L);
        ticket.close();

        verify(store).complete("key", "token", 7L);
        verify(store, never()).release(anyString(), anyString());
    }

    @Test
    void shouldCallStoreOnlyOnceWhenReleasedTwice() {
        IdempotencyTicket ticket = new IdempotencyTicket(store, "key", "token");

        ticket.release();
        ticket.close();

        verify(store, times(1)).release("key", "token");
    }

    @Test
    void shouldSwallowStoreErrorsAfterDatabaseOutcomeIsKnown() {
        doThrow(new IdempotencyStoreUnavailableException("down", null)).when(store).complete(anyString(), anyString(), anyLong());
        doThrow(new IdempotencyStoreUnavailableException("down", null)).when(store).release(anyString(), anyString());

        assertDoesNotThrow(() -> new IdempotencyTicket(store, "k1", "t").complete(1L));
        assertDoesNotThrow(() -> new IdempotencyTicket(store, "k2", "t").release());
    }
}
