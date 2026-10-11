package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.WebhookVerifier;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.springframework.stereotype.Component;

import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;

@Component
public class BankAdapter implements PaymentProvider, WebhookVerifier {

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.BANK_TRANSFER;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public ProviderCheckoutResult processPayment(ProviderCheckoutRequest request) {
        throw new UnsupportedOperationException("BANK_TRANSFER provider is not enabled");
    }

    @Override
    public PaymentVerificationResult verify(Object payload) {
        throw new UnsupportedOperationException("BANK_TRANSFER webhook verification is not enabled");
    }
}
