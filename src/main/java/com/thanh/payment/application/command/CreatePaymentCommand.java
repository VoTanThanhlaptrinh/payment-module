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
    // Giá trị header Idempotency-Key do client gửi (8-128 ký tự ASCII).
    private final String idempotencyKey;
    // IP người gọi lấy từ request, chuyển tiếp làm vnp_IpAddr.
    private final String clientIp;
}
