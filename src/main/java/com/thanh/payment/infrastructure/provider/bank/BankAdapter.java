package com.thanh.payment.infrastructure.provider.bank;

import com.thanh.payment.application.provider.PaymentVerificationResult;
import com.thanh.payment.application.provider.PaymentProvider;
import com.thanh.payment.application.provider.WebhookVerifier;
import com.thanh.payment.domain.enums.PaymentMethodType;
import org.springframework.stereotype.Component;

import com.thanh.payment.application.provider.ProviderCheckoutRequest;
import com.thanh.payment.application.provider.ProviderCheckoutResult;

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
