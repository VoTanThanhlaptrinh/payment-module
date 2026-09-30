package com.thanh.payment.application.port.out;

import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;

public interface PaymentProvider {
    PaymentMethodType getSupportedType();
    void processPayment(Payment payment);
}
