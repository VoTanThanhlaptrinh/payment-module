package com.thanh.payment.domain.model;

/**
 * Trạng thái của một Payment (Aggregate Root).
 */
public enum PaymentStatus {
    // Đã tạo, đang chờ khách thanh toán / chờ kết quả từ VNPay.
    PENDING,
    // VNPay báo nghi ngờ (mã 07): chờ người duyệt. Vẫn tính là "đang hoạt động" nên order chưa được tạo payment mới.
    REVIEW_REQUIRED,
    // Đã thanh toán thành công.
    COMPLETED,
    // Thất bại (bị từ chối/huỷ). Order được phép tạo lại payment mới.
    FAILED,
    // Quá hạn thanh toán.
    EXPIRED,
    // Đã hoàn tiền toàn bộ (chưa triển khai luồng refund).
    REFUNDED,
    // Đã hoàn tiền một phần (chưa triển khai luồng refund).
    PARTIALLY_REFUNDED
}
