package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.dto.webhook.VnpayIpnRequest;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.springframework.stereotype.Component;

@Component
public class VnpayAdapter implements PaymentProvider {

    @Override
    public PaymentMethodType getSupportedType() {
        return PaymentMethodType.VNPAY;
    }

    @Override
    public void processPayment(Payment payment) {
        // TODO: Implement VNPay API call
    }

    @Override
    public PaymentVerificationResult verify(Object payload) {
        if (!(payload instanceof VnpayIpnRequest)) {
            throw new IllegalArgumentException("Invalid payload type for VNPAY");
        }
        VnpayIpnRequest request = (VnpayIpnRequest) payload;

        // TODO: Validate request.getVnp_SecureHash() with HMAC-SHA512 checksum
        boolean isSignatureValid = request.getVnp_SecureHash() != null 
                && !request.getVnp_SecureHash().isBlank() 
                && !"INVALID_SIGNATURE".equals(request.getVnp_SecureHash());

        boolean isSuccess = "00".equals(request.getVnp_ResponseCode()) 
                && "00".equals(request.getVnp_TransactionStatus());

        Long orderId = null;
        if (request.getVnp_TxnRef() != null && !request.getVnp_TxnRef().isBlank()) {
            try {
                orderId = Long.valueOf(request.getVnp_TxnRef());
            } catch (NumberFormatException ignored) {
            }
        }

        return PaymentVerificationResult.builder()
                .isSignatureValid(isSignatureValid)
                .isSuccess(isSuccess)
                .orderId(orderId)
                .transactionId(request.getVnp_TxnRef())
                .providerTransactionId(request.getVnp_TransactionNo())
                .failureReason(!isSuccess ? "VNPay response code: " + request.getVnp_ResponseCode() : null)
                .build();
    }
}
