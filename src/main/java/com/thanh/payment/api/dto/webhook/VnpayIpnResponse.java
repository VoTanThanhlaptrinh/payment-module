package com.thanh.payment.api.dto.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;

// Body trả cho VNPay sau IPN. VNPay chỉ đọc RspCode: 00 = đã ghi nhận (VNPay ngừng gọi lại), 01 = không thấy đơn, 02 = đã xử lý trước đó, 04 = sai số tiền, 97 = sai chữ ký, 99 = lỗi khác.
public record VnpayIpnResponse(
        @JsonProperty("RspCode") String rspCode,
        @JsonProperty("Message") String message
) {
    // Xử lý thành công (kể cả khi kết quả giao dịch là thất bại/hết hạn: miễn là đã GHI NHẬN xong).
    public static VnpayIpnResponse processed() {
        return new VnpayIpnResponse("00", "Confirm Success");
    }

    // IPN lặp lại hoặc giao dịch đã kết thúc: trả 02 và không đổi gì (idempotent).
    public static VnpayIpnResponse alreadyConfirmed() {
        return new VnpayIpnResponse("02", "Order already confirmed");
    }

    // vnp_TxnRef không tương ứng giao dịch nào.
    public static VnpayIpnResponse notFound() {
        return new VnpayIpnResponse("01", "Order not found");
    }

    // vnp_Amount khác số tiền đã lưu x 100: không tin, giữ nguyên trạng thái.
    public static VnpayIpnResponse amountMismatch() {
        return new VnpayIpnResponse("04", "Invalid amount");
    }

    // Chữ ký HMAC không khớp: có thể bị giả mạo, bỏ qua trước khi chạm DB.
    public static VnpayIpnResponse invalidSignature() {
        return new VnpayIpnResponse("97", "Invalid signature");
    }

    // Dữ liệu thiếu/méo, TmnCode sai, mã kết quả lạ, hoặc lỗi nội bộ: không đổi trạng thái.
    public static VnpayIpnResponse invalidRequest() {
        return new VnpayIpnResponse("99", "Invalid request");
    }
}
