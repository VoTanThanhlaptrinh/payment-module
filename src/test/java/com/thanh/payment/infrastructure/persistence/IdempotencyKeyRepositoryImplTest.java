package com.thanh.payment.infrastructure.persistence;

import com.thanh.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.thanh.payment.domain.model.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyRepositoryImplTest {

    @Mock IdempotencyKeyJpaRepository jpaRepository;
    private IdempotencyKeyRepositoryImpl repository;
    private final IdempotencyKey key = IdempotencyKey.start("k", "r", OffsetDateTime.now().plusHours(1));

    @BeforeEach
    void setUp() {
        repository = new IdempotencyKeyRepositoryImpl(jpaRepository);
    }

    @Test
    void shouldTranslateIdempotencyKeyUniqueViolation() {
        when(jpaRepository.saveAndFlush(any())).thenThrow(PaymentRepositoryImplTest.violation("uq_idempotency_scope_key"));

        assertThrows(DuplicateIdempotencyKeyException.class, () -> repository.saveAndFlush(key));
    }

    @Test
    void shouldRethrowOtherConstraintViolationsUnchanged() {
        DataIntegrityViolationException other = PaymentRepositoryImplTest.violation("fk_idempotency_payment");
        when(jpaRepository.saveAndFlush(any())).thenThrow(other);

        assertSame(other, assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(key)));
    }
}
