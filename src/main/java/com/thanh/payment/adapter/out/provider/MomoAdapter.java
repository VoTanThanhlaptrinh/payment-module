package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.springframework.stereotype.Component;

@Component
public class MomoAdapter implements PaymentProvider {

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.MOMO;
    }

    @Override
    public void processPayment(Payment payment) {
        // TODO: Implement Momo API call
    }
}
