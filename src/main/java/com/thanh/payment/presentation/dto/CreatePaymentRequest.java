package com.thanh.payment.presentation.dto;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CreatePaymentRequest {

    // Mã đơn hàng của merchant, phải là số dương.
    @NotNull(message = "Order ID is required")
    @Positive(message = "Order ID must be positive")
    private Long orderId;

    // Số tiền VND: tối thiểu 5.000, số nguyên, tối đa 10 chữ số (nhân 100 sẽ vừa giới hạn 12 chữ số của vnp_Amount). Quy tắc này được PaymentService kiểm tra lại độc lập.
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "5000", message = "VNPay amount must be at least 5000 VND")
    @Digits(integer = 10, fraction = 0, message = "VNPay amount must be a whole VND amount within protocol limits")
    private BigDecimal amount;

    // Hiện chỉ VND mới được VNPay chấp nhận.
    @NotNull(message = "Currency is required")
    private Currency currency;

    // Phương thức thanh toán. Hiện chỉ VNPAY được bật.
    @NotNull(message = "Payment method is required")
    private PaymentMethodType paymentMethod;

    public CreatePaymentCommand toCommand() {
        return toCommand(null, null);
    }

    // Đổi request HTTP thành command của tầng application, ghép thêm Idempotency-Key và IP khách (những thứ không nằm trong body).
    public CreatePaymentCommand toCommand(String idempotencyKey, String clientIp) {
        return CreatePaymentCommand.builder()
                .orderId(this.orderId)
                .amount(this.amount)
                .currency(this.currency)
                .method(this.paymentMethod)
                .idempotencyKey(idempotencyKey)
                .clientIp(clientIp)
                .build();
    }
}
