package com.thanh.payment.api.dto;

import com.thanh.payment.domain.enums.PaymentMethodType;
import com.thanh.payment.domain.enums.PaymentStatus;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Builder
public class PaymentResponse {
    @Deprecated
    private final Long id;
    private final Long paymentId;
    private final Long orderId;
    private final BigDecimal totalAmount;
    private final Currency currency;
    private final PaymentMethodType method;
    private final PaymentStatus status;
    private final OffsetDateTime createdAt;
    private final String transactionId;
    private final String checkoutUrl;
    private final OffsetDateTime expiresAt;
}
