package com.thanh.payment.domain.exception;

/**
 * Idempotency-Key này đã có trong DB (vi phạm unique scope + key_hash).
 * Tầng application xử lý bằng cách trả lại kết quả cũ hoặc báo conflict.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(Throwable cause) {
        super("Idempotency key already exists", cause);
    }
}
