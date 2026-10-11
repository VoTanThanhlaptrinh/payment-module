package com.thanh.payment.infrastructure.cache;

import com.thanh.payment.TestcontainersConfiguration;
import com.thanh.payment.application.port.out.IdempotencyStore;
import com.thanh.payment.application.port.out.IdempotencyStore.Existing;
import com.thanh.payment.application.port.out.IdempotencyStore.Reservation;
import com.thanh.payment.application.port.out.IdempotencyStore.Reserved;
import com.thanh.payment.application.port.out.IdempotencyStore.State;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class RedisIdempotencyStoreIntegrationTest {

    @Autowired IdempotencyStore store;
    @Autowired StringRedisTemplate redis;

    @Test
    void onlyOneOfManyConcurrentReservationsWins() throws Exception {
        String keyHash = newKey();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Reservation>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return store.reserve(keyHash, "req");
            }));
        }
        ready.await();
        go.countDown();

        int reserved = 0;
        int inProgress = 0;
        for (Future<Reservation> future : futures) {
            Reservation reservation = future.get(10, TimeUnit.SECONDS);
            if (reservation instanceof Reserved) {
                reserved++;
            } else if (reservation instanceof Existing existing && existing.state() == State.IN_PROGRESS) {
                inProgress++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, reserved);
        assertEquals(threads - 1, inProgress);
    }

    @Test
    void completeStoresPaymentIdAndLongTtl() {
        String keyHash = newKey();
        Reserved reserved = assertInstanceOf(Reserved.class, store.reserve(keyHash, "req"));

        Long inProgressTtl = redis.getExpire("payment:idem:" + keyHash, TimeUnit.SECONDS);
        assertTrue(inProgressTtl > 0 && inProgressTtl <= 30, "TTL IN_PROGRESS ngắn: " + inProgressTtl);

        store.complete(keyHash, reserved.token(), 42L);

        Existing existing = assertInstanceOf(Existing.class, store.reserve(keyHash, "req"));
        assertEquals(State.COMPLETED, existing.state());
        assertEquals(42L, existing.paymentId());
        assertEquals("req", existing.requestHash());
        Long completedTtl = redis.getExpire("payment:idem:" + keyHash, TimeUnit.SECONDS);
        assertTrue(completedTtl > 23 * 3600, "TTL COMPLETED ~24h: " + completedTtl);
    }

    @Test
    void completeWithWrongTokenChangesNothing() {
        String keyHash = newKey();
        store.reserve(keyHash, "req");

        store.complete(keyHash, "someone-else", 42L);

        Existing existing = assertInstanceOf(Existing.class, store.reserve(keyHash, "req"));
        assertEquals(State.IN_PROGRESS, existing.state());
        assertNull(existing.paymentId());
    }

    @Test
    void releaseOnlyRemovesOwnInProgressKey() {
        String keyHash = newKey();
        Reserved reserved = assertInstanceOf(Reserved.class, store.reserve(keyHash, "req"));

        store.release(keyHash, "someone-else");
        assertInstanceOf(Existing.class, store.reserve(keyHash, "req"));

        store.release(keyHash, reserved.token());
        assertInstanceOf(Reserved.class, store.reserve(keyHash, "req"));
    }

    @Test
    void releaseDoesNotRemoveCompletedKey() {
        String keyHash = newKey();
        Reserved reserved = assertInstanceOf(Reserved.class, store.reserve(keyHash, "req"));
        store.complete(keyHash, reserved.token(), 5L);

        store.release(keyHash, reserved.token());

        assertEquals(State.COMPLETED, assertInstanceOf(Existing.class, store.reserve(keyHash, "req")).state());
    }

    @Test
    void malformedRecordIsTreatedAsInProgressInsteadOfFailing() {
        String keyHash = newKey();
        // bản ghi hỏng: có token nhưng thiếu state và requestHash
        redis.opsForHash().put("payment:idem:" + keyHash, "token", "orphan");

        Existing existing = assertInstanceOf(Existing.class, store.reserve(keyHash, "req"));

        assertEquals(State.IN_PROGRESS, existing.state());
        assertNull(existing.requestHash());
        assertNull(existing.paymentId());
    }

    private static String newKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
