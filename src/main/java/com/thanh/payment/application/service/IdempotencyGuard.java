package com.thanh.payment.application.service;

import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.port.out.IdempotencyStore;
import com.thanh.payment.application.port.out.IdempotencyStore.Existing;
import com.thanh.payment.application.port.out.IdempotencyStore.IdempotencyStoreUnavailableException;
import com.thanh.payment.application.port.out.IdempotencyStore.Reservation;
import com.thanh.payment.application.port.out.IdempotencyStore.Reserved;
import com.thanh.payment.application.port.out.IdempotencyStore.State;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Gom phần "xin khoá + kiểm tra trùng" cho một Idempotency-Key vào một chỗ.
 * <p>
 * Người gọi chỉ cần: {@link #acquire} -> làm việc -> {@link IdempotencyTicket#complete} (hoặc để close() nhả khoá),
 * không cần biết Redis, token, TTL hay mã lỗi idempotency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final IdempotencyStore store;

    public sealed interface Acquisition permits Acquired, AlreadyCompleted {
    }

    // Request này giữ key: được phép làm tiếp.
    public record Acquired(IdempotencyTicket ticket) implements Acquisition {
    }

    // Cùng key + cùng nội dung đã tạo xong trước đó: không làm lại, trả kết quả cũ theo paymentId.
    public record AlreadyCompleted(long paymentId) implements Acquisition {
    }

    /**
     * Xin giữ key. Nguyên tử ở kho (Redis): hai request cùng key đến cùng lúc, chỉ một được {@link Acquired}.
     *   - Cùng key nhưng khác nội dung => 409 IDEMPOTENCY_CONFLICT
     *   - Request trước còn đang xử lý  => 409 IDEMPOTENCY_IN_PROGRESS (Retry-After 1)
     *   - Request trước đã xong         => {@link AlreadyCompleted}
     *   - Kho không truy cập được       => 503 IDEMPOTENCY_STORE_UNAVAILABLE (Retry-After 5), không tạo gì
     */
    public Acquisition acquire(String keyHash, String requestHash) {
        Reservation reservation;
        try {
            reservation = store.reserve(keyHash, requestHash);
        } catch (IdempotencyStoreUnavailableException exception) {
            // Chỉ log kết quả, KHÔNG log key hay hash: key do client chọn và có thể bị dò.
            log.warn("Idempotency outcome=STORE_UNAVAILABLE");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDEMPOTENCY_STORE_UNAVAILABLE",
                    "Idempotency store is temporarily unavailable", 5);
        }
        if (reservation instanceof Reserved reserved) {
            log.debug("Idempotency outcome=RESERVED");
            return new Acquired(new IdempotencyTicket(store, keyHash, reserved.token()));
        }
        Existing existing = (Existing) reservation;
        // requestHash null = bản ghi Redis hỏng: không so sánh được, từ chối an toàn bằng "đang xử lý".
        if (existing.requestHash() != null && !existing.requestHash().equals(requestHash)) {
            log.warn("Idempotency outcome=CONFLICT");
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used with a different request");
        }
        if (existing.state() == State.COMPLETED && existing.paymentId() != null) {
            log.info("Idempotency outcome=REPLAY paymentId={}", existing.paymentId());
            return new AlreadyCompleted(existing.paymentId());
        }
        log.info("Idempotency outcome=IN_PROGRESS");
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                "A request with this idempotency key is still in progress", 1);
    }
}
