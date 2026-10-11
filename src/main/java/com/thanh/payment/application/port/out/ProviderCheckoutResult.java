package com.thanh.payment.application.port.out;

import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

@Getter
@Builder
public class ProviderCheckoutResult {
    private final String transactionId;
    // URL đã ký để redirect khách sang cổng thanh toán.
    private final String checkoutUrl;
    private final OffsetDateTime expiresAt;
    private final ProviderCheckoutStatus status;
}
