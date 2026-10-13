package com.thanh.payment.domain.enums;

/**
 * Trạng thái của từng Transaction.
 */
public enum TransactionStatus {
    // Đã gửi khách đi thanh toán, chưa có kết quả. Đây là trạng thái duy nhất mà IPN/đối soát được phép xử lý.
    PENDING,
    // Cần duyệt thủ công (VNPay mã 07). Được coi là đã kết thúc (isTerminal).
    REVIEW_REQUIRED,
    // Thành công (IPN 00/00 hoặc QueryDR 00).
    SUCCESS,
    // Thất bại.
    FAILED,
    // Hết hạn (VNPay mã 11).
    EXPIRED
}
