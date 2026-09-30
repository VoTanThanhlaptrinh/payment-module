package com.thanh.payment.application.port.in;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.domain.model.PaymentMethodType;

public interface VerifyWebhookUseCase {
    PaymentVerificationResult verifyWebhook(PaymentMethodType type, Object payload);
}
