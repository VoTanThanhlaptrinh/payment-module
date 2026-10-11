package com.thanh.payment.presentation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Builder
public class ApiErrorResponse {
    private final String code;
    private final String message;
    private final String traceId;
    private final OffsetDateTime timestamp;
    private final Map<String, String> fieldErrors;
}
