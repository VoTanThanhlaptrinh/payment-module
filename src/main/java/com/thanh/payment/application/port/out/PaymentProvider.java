package com.thanh.payment.application.port.out;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.domain.model.PaymentMethodType;

public interface PaymentProvider {
    PaymentMethodType getSupportedType();
    ProviderCheckoutResult processPayment(ProviderCheckoutRequest request);
    PaymentVerificationResult verify(Object payload);
}
