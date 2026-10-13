package com.thanh.payment.application.provider;

import com.thanh.payment.domain.enums.PaymentMethodType;

public interface WebhookVerifier {
    PaymentMethodType getSupportedType();

    PaymentVerificationResult verify(Object payload);
}
