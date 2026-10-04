package com.thanh.payment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVerificationResult {
    private boolean isSignatureValid;
    private boolean isSuccess;
    private Long orderId;
    private String transactionId;
    private String providerTransactionId;
    private String failureReason;
}
