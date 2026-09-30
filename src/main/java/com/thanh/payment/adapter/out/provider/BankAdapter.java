package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.springframework.stereotype.Component;

@Component
public class BankAdapter implements PaymentProvider {

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.BANK_TRANSFER;
    }

    @Override
    public void processPayment(Payment payment) {
        // TODO: Implement Bank Transfer API call
    }
}
