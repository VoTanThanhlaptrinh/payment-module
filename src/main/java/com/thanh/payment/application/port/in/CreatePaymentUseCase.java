package com.thanh.payment.application.port.in;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.dto.PaymentResponse;

public interface CreatePaymentUseCase {
    PaymentResponse createPayment(CreatePaymentCommand command);
}
