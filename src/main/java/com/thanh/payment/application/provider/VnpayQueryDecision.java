package com.thanh.payment.application.provider;

public enum VnpayQueryDecision {
    // Thanh toán thành công.
    SUCCESS,
    // Thanh toán thất bại.
    FAILED,
    // Cần xem xét thủ công.
    REVIEW_REQUIRED,
    // VNPay trả lời hợp lệ nhưng giao dịch chưa có kết quả (TransactionStatus 01).
    PENDING,
    // VNPay xác nhận (response ký đúng, mã 91) KHÔNG có giao dịch này. Chỉ có nghĩa "hết hạn" khi đã quá hạn thanh toán + thời gian chờ.
    NOT_FOUND,
    // Chưa tin được kết quả (lỗi mạng, sai chữ ký, dữ liệu không khớp...): giữ nguyên, thử lại lần sau.
    RETRY
}
