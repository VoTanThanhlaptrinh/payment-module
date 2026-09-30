package com.thanh.payment.application.port.in;

import com.thanh.payment.application.command.ProcessTransactionCommand;

public interface ProcessTransactionUseCase {
    void processTransaction(ProcessTransactionCommand command);
}
