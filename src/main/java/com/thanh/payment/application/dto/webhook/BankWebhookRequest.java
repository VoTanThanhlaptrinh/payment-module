package com.thanh.payment.application.dto.webhook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankWebhookRequest {
    private String code;
    private String desc;
    private boolean success;
    private BankWebhookData data;
    private String signature;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankWebhookData {
        private Long orderCode;
        private Long amount;
        private String description;
        private String reference;
    }
}
