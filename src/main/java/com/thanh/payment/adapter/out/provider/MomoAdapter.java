package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.dto.webhook.MomoIpnRequest;
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

    @Override
    public PaymentVerificationResult verify(Object payload) {
        if (!(payload instanceof MomoIpnRequest)) {
            throw new IllegalArgumentException("Invalid payload type for MOMO");
        }
        MomoIpnRequest request = (MomoIpnRequest) payload;

        // TODO: Validate request.getSignature() with HMAC-SHA256 checksum
        boolean isSignatureValid = request.getSignature() != null 
                && !request.getSignature().isBlank() 
                && !"INVALID_SIGNATURE".equals(request.getSignature());

        boolean isSuccess = request.getResultCode() != null && request.getResultCode() == 0;

        Long orderId = null;
        if (request.getOrderId() != null && !request.getOrderId().isBlank()) {
            try {
                orderId = Long.valueOf(request.getOrderId());
            } catch (NumberFormatException ignored) {
            }
        }

        String transactionId = request.getTransId() != null ? String.valueOf(request.getTransId()) : null;

        return PaymentVerificationResult.builder()
                .isSignatureValid(isSignatureValid)
                .isSuccess(isSuccess)
                .orderId(orderId)
                .transactionId(transactionId)
                .build();
    }
}
