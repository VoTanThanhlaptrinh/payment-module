package com.thanh.payment.application.port.out;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.domain.model.PaymentMethodType;

public interface WebhookVerifier {
    PaymentMethodType getSupportedType();

    PaymentVerificationResult verify(Object payload);
}
