package com.thanh.payment.application.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessTransactionCommand {
    private Long orderId;
    private String transactionId;
    private String providerTransactionId;
    private String failureReason;
    private boolean success;
}
