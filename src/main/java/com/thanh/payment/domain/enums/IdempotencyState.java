package com.thanh.payment.domain.enums;

public enum IdempotencyState {
    // Request đang được xử lý (đã giữ khoá, chưa tạo xong payment).
    IN_PROGRESS,
    // Đã tạo xong payment; request lặp lại sẽ được replay.
    COMPLETED
}
