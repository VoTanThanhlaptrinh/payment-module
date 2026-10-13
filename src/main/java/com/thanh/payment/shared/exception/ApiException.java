package com.thanh.payment.shared.exception;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ có chủ đích của API: mang HTTP status + mã lỗi (vd IDEMPOTENCY_CONFLICT) + tuỳ chọn Retry-After. GlobalExceptionHandler chuyển thành JSON lỗi chuẩn.
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
