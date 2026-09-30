package com.thanh.payment.application.port.in;

import com.thanh.payment.application.command.RefundCommand;

public interface RefundUseCase {
    void refund(RefundCommand command);
}
