package com.thanh.payment.presentation.dto;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CreatePaymentRequest {

    @NotNull(message = "Order ID is required")
    private Long orderId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "Currency is required")
    private Currency currency;

    @NotNull(message = "Payment method is required")
    private PaymentMethodType paymentMethod;

    public CreatePaymentCommand toCommand() {
        return CreatePaymentCommand.builder()
                .orderId(this.orderId)
                .amount(this.amount)
                .currency(this.currency)
                .method(this.paymentMethod)
                .build();
    }
}
