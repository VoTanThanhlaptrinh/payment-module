package com.thanh.payment.application.port.in;

import com.thanh.payment.application.command.CreatePaymentCommand;

public interface CreatePaymentUseCase {
    void createPayment(CreatePaymentCommand command);
}
