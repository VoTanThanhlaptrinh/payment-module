package com.thanh.payment.application.port.out;

import com.thanh.payment.domain.model.PaymentMethodType;

public interface PaymentProvider {
    PaymentMethodType getSupportedType();
    boolean isEnabled();
    // Dựng phiên thanh toán ở cổng (với VNPay: ký và trả URL redirect). Không gọi mạng với VNPay nên chạy được trong DB transaction; provider gọi mạng thì phải tách ra ngoài.
    ProviderCheckoutResult processPayment(ProviderCheckoutRequest request);

    // Thời hạn thanh toán (phút) của provider, dùng làm expired_at của giao dịch và mốc bắt đầu đối soát.
    default int getExpiryMinutes() {
        return 15;
    }
}
