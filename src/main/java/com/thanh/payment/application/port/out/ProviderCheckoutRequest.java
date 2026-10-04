package com.thanh.payment.application.port.out;

import com.thanh.payment.domain.valueobject.Money;
import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

@Getter
@Builder
public class ProviderCheckoutRequest {
    private final Long paymentId;
    private final Long transactionId;
    private final Long orderId;
    private final Money amount;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;
}
