package com.thanh.payment.application;

public enum VnpayReconciliationOutcome {
    // QueryDR cho kết quả rõ ràng: payment/transaction đã được cập nhật.
    UPDATED,
    // Chưa có kết quả (hoặc lỗi tạm thời): đã ghi nhận lần thử và đặt lịch lần sau.
    RETRY_SCHEDULED,
    // Hết số lần thử: giữ PENDING, reconciliation_exhausted=true, chờ xử lý thủ công.
    EXHAUSTED,
    // Giao dịch đã kết thúc (vd IPN đến trước): bỏ qua, không ghi đè.
    ALREADY_TERMINAL,
    // Dữ liệu cục bộ không hợp lệ (không thấy giao dịch/payment, sai method hoặc loại): không xử lý.
    INVALID_LOCAL_STATE
}
