package com.thanh.payment;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.api.dto.PaymentResponse;
import com.thanh.payment.shared.exception.ApiException;
import com.thanh.payment.application.PaymentService;
import com.thanh.payment.domain.enums.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chạy PaymentService thật trên PostgreSQL + Redis (Testcontainers) để kiểm chứng chống trùng khi nhiều request đến cùng lúc
 * và khi Redis mất dữ liệu (lớp DB phải đỡ).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyEndToEndIntegrationTest {

    @Autowired PaymentService createPayment;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM payments");
        Set<String> keys = redis.keys("payment:idem:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOnePayment() throws Exception {
        long orderId = 1001L;
        String key = newKey();

        List<Object> results = runConcurrently(20, i -> command(orderId, key));

        assertEquals(1, countPayments(orderId));
        long distinctPayments = results.stream().filter(r -> r instanceof PaymentResponse)
                .map(r -> ((PaymentResponse) r).getPaymentId()).distinct().count();
        assertEquals(1, distinctPayments, "mọi response thành công phải cùng một paymentId");
        // Các request còn lại chỉ được phép bị từ chối bằng "đang xử lý" (không có lỗi lạ).
        assertTrue(results.stream().filter(r -> r instanceof ApiException)
                .allMatch(r -> "IDEMPOTENCY_IN_PROGRESS".equals(((ApiException) r).getCode())), results.toString());
        assertTrue(results.stream().anyMatch(r -> r instanceof PaymentResponse));
    }

    @Test
    void concurrentRequestsWithDifferentKeysForSameOrderCreateExactlyOnePayment() throws Exception {
        long orderId = 1002L;

        List<Object> results = runConcurrently(20, i -> command(orderId, newKey()));

        assertEquals(1, countPayments(orderId));
        assertEquals(1, results.stream().filter(r -> r instanceof PaymentResponse).count());
        assertTrue(results.stream().filter(r -> r instanceof ApiException)
                .allMatch(r -> "PAYMENT_ALREADY_PENDING".equals(((ApiException) r).getCode())), results.toString());
    }

    @Test
    void retryAfterRedisLostItsDataReturnsTheOriginalPaymentInsteadOfConflict() {
        long orderId = 1003L;
        String key = newKey();
        PaymentResponse first = createPayment.createPayment(command(orderId, key));

        // Giả lập Redis restart/failover mất toàn bộ key idempotency.
        Set<String> keys = redis.keys("payment:idem:*");
        assertTrue(keys != null && !keys.isEmpty());
        redis.delete(keys);

        PaymentResponse retry = createPayment.createPayment(command(orderId, key));

        assertEquals(first.getPaymentId(), retry.getPaymentId());
        assertEquals(first.getCheckoutUrl(), retry.getCheckoutUrl());
        assertEquals(1, countPayments(orderId));
    }

    private List<Object> runConcurrently(int threads, IntFunction<CreatePaymentCommand> commandFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            CreatePaymentCommand command = commandFor.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return (Object) createPayment.createPayment(command);
                } catch (Exception exception) {
                    return exception;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private int countPayments(long orderId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static CreatePaymentCommand command(long orderId, String key) {
        return CreatePaymentCommand.builder()
                .orderId(orderId).amount(BigDecimal.valueOf(5_000)).currency(Currency.VND)
                .method(PaymentMethodType.VNPAY).idempotencyKey(key).clientIp("127.0.0.1")
                .build();
    }

    private static String newKey() {
        return "e2e-" + UUID.randomUUID();
    }
}
