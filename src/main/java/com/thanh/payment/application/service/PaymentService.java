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
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService implements CreatePaymentUseCase, ProcessTransactionUseCase, RefundUseCase, VerifyWebhookUseCase {

    private final PaymentRepository paymentRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentProviderFactory paymentProviderFactory;

    @Override
    @Transactional
    public PaymentResponse createPayment(CreatePaymentCommand command) {
        Optional<Payment> paymentOptional = paymentRepository.findByOrderIdAndStatus(command.getOrderId(), PaymentStatus.PENDING);
        if (paymentOptional.isPresent()) {
            return handleExistingPendingPayment(paymentOptional.get());
        }

        Money amount = Money.of(command.getAmount(), command.getCurrency());
        Payment newPayment = Payment.create(
                command.getOrderId(),
                amount,
                command.getMethod()
        );
        Payment savedPayment = paymentRepository.save(newPayment);

        return createChargeAndProcess(savedPayment, command.getMethod());
    }

    private PaymentResponse handleExistingPendingPayment(Payment payment) {
        Optional<Transaction> pendingTxOpt = transactionRepository.findPendingChargeByPaymentId(payment.getId());
        if (pendingTxOpt.isEmpty()) {
            return createChargeAndProcess(payment, payment.getMethod());
        }

        Transaction pendingTx = pendingTxOpt.get();
        if (pendingTx.isExpired()) {
            pendingTx.markExpired();
            transactionRepository.save(pendingTx);
            throw new IllegalStateException("Payment transaction has expired");
        }

        return PaymentMapper.toResponseDTO(payment);
    }

    private PaymentResponse createChargeAndProcess(Payment payment, PaymentMethodType method) {
        // VN-07: Tạo và lưu charge transaction trước khi dựng checkout URL để có merchant reference duy nhất
        Transaction chargeTx = Transaction.createCharge(
                payment.getId(),
                payment.getTotalAmount(),
                OffsetDateTime.now().plusMinutes(15)
        );
        transactionRepository.save(chargeTx);

        PaymentProvider provider = paymentProviderFactory.getProvider(method);
        
        ProviderCheckoutRequest req = ProviderCheckoutRequest.builder()
                .paymentId(payment.getId())
                .transactionId(chargeTx.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getTotalAmount())
                .createdAt(payment.getCreatedAt())
                .expiresAt(chargeTx.getExpiredAt())
                .build();

        ProviderCheckoutResult result = provider.processPayment(req);

        return PaymentMapper.toResponseDTO(payment, result);
    }

    @Override
    @Transactional
    public void processTransaction(ProcessTransactionCommand command) {
        // 1. Tìm Transaction tương ứng với dữ liệu callback từ cổng thanh toán
        Transaction transaction = findTransaction(command);

        // 2. Tìm Payment tương ứng từ paymentId của Transaction (đảm bảo tính nhất quán dữ liệu)
        Payment payment = paymentRepository.findById(transaction.getPaymentId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Không tìm thấy Payment tương ứng với Transaction ID: %s (paymentId: %s)",
                                transaction.getId(), transaction.getPaymentId())
                ));

        // 3. Cập nhật trạng thái thành công hoặc thất bại cho cả 2 entity
        if (command.isSuccess()) {
            applyTransactionSuccess(payment, transaction, command);
        } else {
            applyTransactionFailure(payment, transaction, command);
        }

        // 4. Lưu đồng thời Payment và Transaction trong cùng transaction database (VN-08)
        paymentRepository.save(payment);
        transactionRepository.save(transaction);
    }

    /**
     * Tra cứu Transaction từ thông tin command callback.
     * Ưu tiên tìm theo TSID (vnp_TxnRef), sau đó tìm theo providerTransactionId,
     * và cuối cùng fallback qua orderId.
     */
    private Transaction findTransaction(ProcessTransactionCommand command) {
        // Ưu tiên tra cứu theo TSID transactionId (VN-04/VN-07)
        if (command.getTransactionId() != null) {
            try {
                Long txId = Long.valueOf(command.getTransactionId());
                return transactionRepository.findById(txId)
                        .orElseThrow(() -> new IllegalStateException("Không tìm thấy Transaction với ID: " + txId));
            } catch (NumberFormatException ignored) {
                // transactionId là chuỗi ký tự của đối tác khác (không phải Long TSID)
            }

            // Thử tra cứu theo mã đối tác cung cấp (provider_transaction_id)
            Optional<Transaction> txByProviderOpt = transactionRepository.findByProviderTransactionId(command.getTransactionId());
            if (txByProviderOpt.isPresent()) {
                return txByProviderOpt.get();
            }
        }

        // Fallback: Tìm qua orderId
        Payment payment = paymentRepository.findByOrderIdAndStatus(command.getOrderId(), PaymentStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy Payment PENDING với orderId: " + command.getOrderId()));

        return transactionRepository.findPendingChargeByPaymentId(payment.getId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy Transaction PENDING cho payment ID: " + payment.getId()));
    }

    /**
     * Cập nhật trạng thái giao dịch thành công.
     */
    private void applyTransactionSuccess(Payment payment, Transaction transaction, ProcessTransactionCommand command) {
        String providerTxId = command.getProviderTransactionId() != null
                ? command.getProviderTransactionId()
                : command.getTransactionId();

        payment.markCompleted();
        transaction.markSuccess(providerTxId);
    }

    /**
     * Cập nhật trạng thái giao dịch thất bại kèm lý do.
     */
    private void applyTransactionFailure(Payment payment, Transaction transaction, ProcessTransactionCommand command) {
        payment.markFailed();
        transaction.markFailed(command.getFailureReason());
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
                .providerTransactionId(result.getProviderTransactionId())
                .failureReason(result.getFailureReason())
                .success(result.isSuccess())
                .build();
        this.processTransaction(ptCommand);

        return result;
    }

    @Override
    public void refund(RefundCommand command) {
    }
}
