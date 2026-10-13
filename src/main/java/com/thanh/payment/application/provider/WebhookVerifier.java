package com.thanh.payment.application.provider;

import com.thanh.payment.domain.model.PaymentMethodType;

public interface WebhookVerifier {
    PaymentMethodType getSupportedType();

    PaymentVerificationResult verify(Object payload);
}
