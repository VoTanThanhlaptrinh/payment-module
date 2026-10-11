package com.thanh.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.dao.DataIntegrityViolationException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void shouldCreatePaymentTransactionAndIdempotencyTables() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('payments', 'transactions', 'idempotency_keys', 'flyway_schema_history')
                """, Integer.class);
        assertEquals(4, count);

        Integer reconciliationColumns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'transactions'
                  AND column_name IN (
                    'reconciliation_attempts',
                    'next_reconciliation_at',
                    'last_reconciliation_at',
                    'last_reconciliation_code',
                    'reconciliation_exhausted')
                """, Integer.class);
        assertEquals(5, reconciliationColumns);

        Integer reconciliationIndex = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'idx_transactions_reconciliation_due'
                """, Integer.class);
        assertEquals(1, reconciliationIndex);
    }

    @Test
    void shouldEnforceActivePaymentAndTransactionConstraints() {
        insertPayment(1L, 500L, "PENDING");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPayment(2L, 500L, "PENDING"));

        jdbcTemplate.update("""
                INSERT INTO transactions
                    (id, payment_id, amount, currency, type, status, version, created_at, expired_at)
                VALUES (?, ?, 5000, 'VND', 'CHARGE', 'PENDING', 0, now(), now() + interval '15 minutes')
                """, 10L, 1L);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO transactions
                    (id, payment_id, amount, currency, type, status, version, created_at, expired_at)
                VALUES (?, ?, 5000, 'VND', 'CHARGE', 'PENDING', 0, now(), now() + interval '15 minutes')
                """, 11L, 1L));
    }

    private void insertPayment(Long id, Long orderId, String status) {
        jdbcTemplate.update("""
                INSERT INTO payments
                    (id, order_id, total_amount, currency, method, status, version, created_at, updated_at)
                VALUES (?, ?, 5000, 'VND', 'VNPAY', ?, 0, now(), now())
                """, id, orderId, status);
    }
}
