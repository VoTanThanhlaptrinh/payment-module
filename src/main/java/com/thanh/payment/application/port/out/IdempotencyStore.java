package com.thanh.payment.application.port.out;

/**
 * Kho trạng thái Idempotency-Key, dùng để chặn request trùng TRƯỚC khi chạm DB.
 * <p>
 * Mỗi key có một bản ghi gồm state, token của request đang giữ, request hash và payment id.
 * Thao tác "kiểm tra đã có chưa rồi mới ghi" phải nguyên tử ở phía cài đặt (Redis: Lua script),
 * nếu không hai request đến cùng lúc có thể cùng lọt qua bước kiểm tra.
 * Kho không truy cập được => ném {@link IdempotencyStoreUnavailableException}.
 */
public interface IdempotencyStore {

    /**
     * Chưa có key: ghi IN_PROGRESS kèm token mới (TTL ngắn) và trả {@link Reserved}.
     * Đã có key: không ghi gì, trả nội dung hiện có dưới dạng {@link Existing}.
     */
    Reservation reserve(String keyHash, String requestHash);

    // Token khớp: chuyển COMPLETED, lưu paymentId, gia hạn TTL dài (mặc định 24h). Token không khớp: không làm gì.
    void complete(String keyHash, String token, long paymentId);

    // Token khớp và vẫn IN_PROGRESS: xoá key để client gửi lại được. Ngược lại: không làm gì.
    void release(String keyHash, String token);

    enum State {
        IN_PROGRESS,
        COMPLETED
    }

    sealed interface Reservation permits Reserved, Existing {
    }

    // Request này giữ key; token dùng để complete/release đúng bản ghi của mình.
    record Reserved(String token) implements Reservation {
    }

    // Key đã có người giữ hoặc đã xong; paymentId chỉ có khi state = COMPLETED.
    record Existing(State state, String requestHash, Long paymentId) implements Reservation {
    }

    class IdempotencyStoreUnavailableException extends RuntimeException {
        public IdempotencyStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
