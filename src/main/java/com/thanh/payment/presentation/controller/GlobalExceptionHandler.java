package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.presentation.dto.ApiErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Gom mọi lỗi của controller về một dạng JSON thống nhất: code, message, traceId, timestamp, fieldErrors. Lỗi lạ (Exception) chỉ trả INTERNAL_ERROR, không lộ chi tiết nội bộ.
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Lỗi nghiệp vụ chủ động: 400/409... kèm header Retry-After nếu có (vd khi key đang được xử lý).
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getRetryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()));
        }
        return new ResponseEntity<>(error(exception.getCode(), exception.getMessage(), Map.of()),
                headers, exception.getStatus());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Request validation failed", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(error("MISSING_HEADER", exception.getMessage(), Map.of()));
    }

    // Body JSON hỏng hoặc sai kiểu là lỗi của bên gọi (400), không phải lỗi hệ thống (500).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(error("INVALID_BODY", "Request body is missing or malformed", Map.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "An unexpected error occurred", Map.of()));
    }

    private ApiErrorResponse error(String code, String message, Map<String, String> fields) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        return ApiErrorResponse.builder()
                .code(code)
                .message(message)
                .traceId(traceId)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .fieldErrors(fields)
                .build();
    }
}
