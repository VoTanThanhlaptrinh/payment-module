package com.thanh.payment.application.port.out;

import com.thanh.payment.domain.model.PaymentMethodType;

public interface PaymentProvider {
    PaymentMethodType getSupportedType();
    boolean isEnabled();
    // Dựng phiên thanh toán ở cổng (với VNPay: ký và trả URL redirect). Không gọi mạng với VNPay nên chạy được trong DB transaction; provider gọi mạng thì phải tách ra ngoài.
    ProviderCheckoutResult processPayment(ProviderCheckoutRequest request);
}
