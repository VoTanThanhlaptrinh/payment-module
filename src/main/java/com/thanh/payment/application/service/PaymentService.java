package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.command.ProcessTransactionCommand;
import com.thanh.payment.application.command.RefundCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.mapper.PaymentMapper;
import com.thanh.payment.application.port.in.CreatePaymentUseCase;
import com.thanh.payment.application.port.in.ProcessTransactionUseCase;
import com.thanh.payment.application.port.in.RefundUseCase;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService implements CreatePaymentUseCase, ProcessTransactionUseCase, RefundUseCase {

    private final PaymentRepository paymentRepository;

    @Override
    @Transactional
    public PaymentResponse createPayment(CreatePaymentCommand command) {
        Optional<Payment> paymenOptional = paymentRepository.findByOrderIdAndStatus(command.getOrderId(), PaymentStatus.PENDING);
        
        if(paymenOptional.isPresent()){
            return PaymentMapper.toResponseDTO(paymenOptional.get());
        }
        
        Money amount = Money.of(command.getAmount(), command.getCurrency());

        Payment payment = Payment.create(
                command.getOrderId(),
                amount,
                command.getMethod()
        );

        Payment savedPayment = paymentRepository.save(payment);
        return PaymentMapper.toResponseDTO(savedPayment);
    }

    @Override
    public void processTransaction(ProcessTransactionCommand command) {
    }

    @Override
    public void refund(RefundCommand command) {
    }
}
