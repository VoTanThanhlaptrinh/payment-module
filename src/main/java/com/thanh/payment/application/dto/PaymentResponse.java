package com.thanh.payment.application.dto;

import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Builder
public class PaymentResponse {
    private final Long id;
    private final Long orderId;
    private final BigDecimal totalAmount;
    private final Currency currency;
    private final PaymentMethodType method;
    private final PaymentStatus status;
    private final OffsetDateTime createdAt;
}
