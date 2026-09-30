package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.command.ProcessTransactionCommand;
import com.thanh.payment.application.command.RefundCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.mapper.PaymentMapper;
import com.thanh.payment.application.port.in.CreatePaymentUseCase;
import com.thanh.payment.application.port.in.ProcessTransactionUseCase;
import com.thanh.payment.application.port.in.RefundUseCase;
import com.thanh.payment.application.port.in.VerifyWebhookUseCase;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService implements CreatePaymentUseCase, ProcessTransactionUseCase, RefundUseCase, VerifyWebhookUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentProviderFactory paymentProviderFactory;

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

        PaymentProvider provider = paymentProviderFactory.getProvider(command.getMethod());
        provider.processPayment(savedPayment);

        return PaymentMapper.toResponseDTO(savedPayment);
    }

    @Override
    @Transactional
    public void processTransaction(ProcessTransactionCommand command) {
        if (command == null || command.getOrderId() == null) {
            return;
        }

        Optional<Payment> paymentOptional = paymentRepository.findByOrderIdAndStatus(command.getOrderId(), PaymentStatus.PENDING);
        if (paymentOptional.isPresent()) {
            Payment payment = paymentOptional.get();
            if (command.isSuccess()) {
                payment.markCompleted();
            } else {
                payment.markFailed();
            }
            paymentRepository.save(payment);
        }
    }

    @Override
    @Transactional
    public PaymentVerificationResult verifyWebhook(PaymentMethodType type, Object payload) {
        PaymentProvider provider = paymentProviderFactory.getProvider(type);
        PaymentVerificationResult result = provider.verify(payload);

        if (!result.isSignatureValid()) {
            throw new SecurityException("Invalid signature from provider: " + type);
        }

        ProcessTransactionCommand ptCommand = ProcessTransactionCommand.builder()
                .orderId(result.getOrderId())
                .transactionId(result.getTransactionId())
                .success(result.isSuccess())
                .build();
        this.processTransaction(ptCommand);

        return result;
    }

    @Override
    public void refund(RefundCommand command) {
    }
}
