package com.thanh.payment.application;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Phiếu" chứng minh request này đang giữ một Idempotency-Key.
 * <p>
 * Dùng với try-with-resources: làm xong thì gọi {@link #complete(long)}; nếu thoát bằng exception mà chưa
 * complete thì {@link #close()} tự nhả khoá để client gửi lại được.
 * Lỗi Redis ở đây KHÔNG ném ra ngoài: lúc gọi các hàm này DB đã commit (hoặc đã rollback), không nên làm hỏng kết quả.
 */
@Slf4j
public final class IdempotencyTicket implements AutoCloseable {

    private final IdempotencyStore store;
    private final String keyHash;
    private final String token;
    private final AtomicBoolean done = new AtomicBoolean(false);

    IdempotencyTicket(IdempotencyStore store, String keyHash, String token) {
        this.store = store;
        this.keyHash = keyHash;
        this.token = token;
    }

    // Đã tạo thanh toán xong: chuyển key sang COMPLETED để request lặp lại được trả kết quả cũ (khoảng 24h).
    public void complete(long paymentId) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        try {
            store.complete(keyHash, token, paymentId);
        } catch (RuntimeException exception) {
            // DB đã có thanh toán; key IN_PROGRESS sẽ tự hết hạn, request lặp lại sau đó được DB (unique) trả kết quả cũ.
            log.warn("Could not mark idempotency key completed errorType={}", exception.getClass().getSimpleName());
        }
    }

    // Thất bại: xoá key IN_PROGRESS của mình. Gọi nhiều lần cũng an toàn.
    public void release() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        try {
            store.release(keyHash, token);
        } catch (RuntimeException exception) {
            // Không nhả được thì TTL ngắn của IN_PROGRESS sẽ tự dọn.
            log.warn("Could not release idempotency key errorType={}", exception.getClass().getSimpleName());
        }
    }

    // Chưa complete thì coi như thất bại và nhả khoá.
    @Override
    public void close() {
        release();
    }
}
