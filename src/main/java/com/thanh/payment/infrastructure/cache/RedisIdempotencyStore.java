package com.thanh.payment.infrastructure.cache;

import com.thanh.payment.application.port.out.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Cài đặt {@link IdempotencyStore} bằng Redis.
 * <p>
 * Mỗi Idempotency-Key là một HASH "payment:idem:&lt;keyHash&gt;" với các trường state, token, requestHash, paymentId.
 * Mọi thao tác "đọc rồi ghi" đều nằm trong Lua script: Redis chạy cả script như MỘT lệnh, không lệnh nào
 * chen vào giữa, nên hai request đến cùng lúc không thể cùng thấy "chưa có" rồi cùng ghi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(IdempotencyStoreProperties.class)
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "payment:idem:";

    // Có rồi: trả state, requestHash, paymentId và KHÔNG ghi. Chưa có: ghi IN_PROGRESS + token + requestHash, đặt TTL, trả mảng rỗng.
    private static final RedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('HMGET', KEYS[1], 'state', 'requestHash', 'paymentId')
            end
            redis.call('HSET', KEYS[1], 'state', 'IN_PROGRESS', 'token', ARGV[1], 'requestHash', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {}
            """, List.class);

    // Chỉ người giữ đúng token mới được chuyển sang COMPLETED (và gia hạn TTL dài).
    private static final RedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'paymentId', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    // Chỉ xoá khi đúng token VÀ vẫn IN_PROGRESS: không bao giờ xoá bản ghi của request khác hay kết quả đã xong.
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'token') == ARGV[1]
                and redis.call('HGET', KEYS[1], 'state') == 'IN_PROGRESS' then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final IdempotencyStoreProperties properties;

    @Override
    @SuppressWarnings("unchecked")
    public Reservation reserve(String keyHash, String requestHash) {
        String token = UUID.randomUUID().toString();
        List<Object> existing = execute(() -> redis.execute(RESERVE_SCRIPT, List.of(key(keyHash)),
                token, requestHash, String.valueOf(properties.getInProgressTtl().toMillis())));
        if (existing == null || existing.isEmpty()) {
            return new Reserved(token);
        }
        return toExisting(existing);
    }

    // Không tin tuyệt đối dữ liệu Redis: bản ghi thiếu/hỏng (state lạ, requestHash trống, paymentId không phải số) được coi là
    // "đang xử lý" để từ chối an toàn (client thử lại sau) thay vì ném lỗi 500 hoặc trả nhầm kết quả.
    private Existing toExisting(List<Object> fields) {
        String state = field(fields, 0);
        String storedRequestHash = field(fields, 1);
        String paymentId = field(fields, 2);
        try {
            if (state == null || storedRequestHash == null || storedRequestHash.isBlank()) {
                throw new IllegalArgumentException("incomplete record");
            }
            State parsed = State.valueOf(state);
            Long id = paymentId == null || paymentId.isBlank() ? null : Long.valueOf(paymentId);
            return new Existing(parsed, storedRequestHash, id);
        } catch (IllegalArgumentException malformed) {
            log.warn("Malformed idempotency record in Redis, treating as in progress");
            return new Existing(State.IN_PROGRESS, null, null);
        }
    }

    private static String field(List<Object> fields, int index) {
        return index < fields.size() && fields.get(index) instanceof String value ? value : null;
    }

    @Override
    public void complete(String keyHash, String token, long paymentId) {
        execute(() -> redis.execute(COMPLETE_SCRIPT, List.of(key(keyHash)),
                token, String.valueOf(paymentId), String.valueOf(properties.getCompletedTtl().toMillis())));
    }

    @Override
    public void release(String keyHash, String token) {
        execute(() -> redis.execute(RELEASE_SCRIPT, List.of(key(keyHash)), token));
    }

    // Lỗi kết nối/timeout của Redis được đổi thành lỗi của port để tầng application không phụ thuộc Spring Data Redis.
    private <T> T execute(java.util.function.Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException exception) {
            throw new IdempotencyStoreUnavailableException("Redis idempotency store is unavailable", exception);
        }
    }

    private static String key(String keyHash) {
        return KEY_PREFIX + keyHash;
    }
}
