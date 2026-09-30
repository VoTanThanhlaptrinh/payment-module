package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.command.ProcessTransactionCommand;
import com.thanh.payment.application.command.RefundCommand;
import com.thanh.payment.application.port.in.CreatePaymentUseCase;
import com.thanh.payment.application.port.in.ProcessTransactionUseCase;
import com.thanh.payment.application.port.in.RefundUseCase;

public class PaymentService implements CreatePaymentUseCase, ProcessTransactionUseCase, RefundUseCase {

    @Override
    public void createPayment(CreatePaymentCommand command) {
    }

    @Override
    public void processTransaction(ProcessTransactionCommand command) {
    }

    @Override
    public void refund(RefundCommand command) {
    }
}
