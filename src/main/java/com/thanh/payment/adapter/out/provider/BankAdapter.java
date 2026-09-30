package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.dto.webhook.BankWebhookRequest;
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

    @Override
    public PaymentVerificationResult verify(Object payload) {
        if (!(payload instanceof BankWebhookRequest)) {
            throw new IllegalArgumentException("Invalid payload type for Bank Transfer");
        }
        BankWebhookRequest request = (BankWebhookRequest) payload;

        // TODO: Validate request.getSignature() with HMAC-SHA256 checksum
        boolean isSignatureValid = request.getSignature() != null 
                && !request.getSignature().isBlank() 
                && !"INVALID_SIGNATURE".equals(request.getSignature());

        boolean isSuccess = "00".equals(request.getCode()) || request.isSuccess();

        Long orderId = request.getData() != null ? request.getData().getOrderCode() : null;
        String transactionId = request.getData() != null ? request.getData().getReference() : null;

        return PaymentVerificationResult.builder()
                .isSignatureValid(isSignatureValid)
                .isSuccess(isSuccess)
                .orderId(orderId)
                .transactionId(transactionId)
                .build();
    }
}
