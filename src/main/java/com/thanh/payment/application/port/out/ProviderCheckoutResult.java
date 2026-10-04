package com.thanh.payment.application.port.out;

import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

@Getter
@Builder
public class ProviderCheckoutResult {
    private final String transactionId;
    private final String checkoutUrl;
    private final OffsetDateTime expiresAt;
}
