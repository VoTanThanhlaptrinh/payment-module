package com.thanh.payment.application.port.in;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.dto.PaymentResponse;

public interface CreatePaymentUseCase {
    // Use case: tạo thanh toán mới (hoặc replay nếu cùng Idempotency-Key). Cài đặt: PaymentService.
    PaymentResponse createPayment(CreatePaymentCommand command);
}
