package com.thanh.payment.application.command;

import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class CreatePaymentCommand {
    private final Long orderId;
    private final BigDecimal amount;
    private final Currency currency;
    private final PaymentMethodType method;
}
