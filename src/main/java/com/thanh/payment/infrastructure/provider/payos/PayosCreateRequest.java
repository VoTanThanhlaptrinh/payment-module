package com.thanh.payment.infrastructure.provider.payos;

// Body POST /v2/payment-requests của payOS (chỉ các trường bắt buộc + expiredAt). expiredAt là Unix timestamp giây kiểu Int32.
record PayosCreateRequest(
        long orderCode,
        long amount,
        String description,
        String cancelUrl,
        String returnUrl,
        int expiredAt,
        String signature
) {
}
