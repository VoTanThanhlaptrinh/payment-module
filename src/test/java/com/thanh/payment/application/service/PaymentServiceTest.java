package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.command.ProcessTransactionCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.*;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Currency;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentProviderFactory paymentProviderFactory;

    @Mock
    private PaymentProvider paymentProvider;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        lenient().when(paymentProviderFactory.getProvider(any())).thenReturn(paymentProvider);
    }

    @Test
    @DisplayName("VN-07: Tạo payment mới phải lưu charge transaction trước khi gọi provider")
    void shouldCreatePaymentAndChargeTransaction() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(101L)
                .amount(BigDecimal.valueOf(250000))
                .currency(Currency.VND)
                .method(PaymentMethodType.VNPAY)
                .build();

        Payment savedPayment = new Payment(1L, 101L, Money.vnd(250000), PaymentMethodType.VNPAY);
        when(paymentRepository.findByOrderIdAndStatus(101L, PaymentStatus.PENDING)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

        com.thanh.payment.application.port.out.ProviderCheckoutResult mockResult = com.thanh.payment.application.port.out.ProviderCheckoutResult.builder()
                .checkoutUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html")
                .transactionId("tx-123")
                .build();
        when(paymentProvider.processPayment(any())).thenReturn(mockResult);

        PaymentResponse response = paymentService.createPayment(command);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals(101L, response.getOrderId());
        assertEquals("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", response.getCheckoutUrl());

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());

        Transaction capturedTx = txCaptor.getValue();
        assertEquals(1L, capturedTx.getPaymentId());
        assertEquals(TransactionType.CHARGE, capturedTx.getType());
        assertEquals(TransactionStatus.PENDING, capturedTx.getStatus());
        assertEquals(Money.vnd(250000), capturedTx.getAmount());
        assertNotNull(capturedTx.getExpiredAt());
        assertTrue(capturedTx.getExpiredAt().isAfter(OffsetDateTime.now()));

        verify(paymentProvider, times(1)).processPayment(any());
    }

    @Test
    @DisplayName("VN-09: Request lặp cho order pending tái sử dụng charge chưa hết hạn, không tạo transaction mới")
    void shouldReusePendingChargeTransactionWhenNotExpired() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(102L)
                .amount(BigDecimal.valueOf(300000))
                .currency(Currency.VND)
                .method(PaymentMethodType.VNPAY)
                .build();

        Payment existingPayment = new Payment(2L, 102L, Money.vnd(300000), PaymentMethodType.VNPAY);
        Transaction pendingTx = Transaction.createCharge(2L, Money.vnd(300000), OffsetDateTime.now().plusMinutes(10));

        when(paymentRepository.findByOrderIdAndStatus(102L, PaymentStatus.PENDING)).thenReturn(Optional.of(existingPayment));
        when(transactionRepository.findPendingChargeByPaymentId(2L)).thenReturn(Optional.of(pendingTx));

        PaymentResponse response = paymentService.createPayment(command);

        assertNotNull(response);
        assertEquals(2L, response.getId());

        // Không tạo mới transaction hay gọi provider lần 2
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(paymentProvider, never()).processPayment(any());
    }

    @Test
    @DisplayName("VN-09: Request lặp cho order pending nhưng charge cũ đã hết hạn thì đánh dấu hết hạn và ném ngoại lệ")
    void shouldThrowExceptionWhenPendingChargeIsExpired() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(103L)
                .amount(BigDecimal.valueOf(400000))
                .currency(Currency.VND)
                .method(PaymentMethodType.VNPAY)
                .build();

        Payment existingPayment = new Payment(3L, 103L, Money.vnd(400000), PaymentMethodType.VNPAY);
        Transaction expiredTx = Transaction.createCharge(3L, Money.vnd(400000), OffsetDateTime.now().minusMinutes(5));

        when(paymentRepository.findByOrderIdAndStatus(103L, PaymentStatus.PENDING)).thenReturn(Optional.of(existingPayment));
        when(transactionRepository.findPendingChargeByPaymentId(3L)).thenReturn(Optional.of(expiredTx));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> paymentService.createPayment(command));
        assertEquals("Payment transaction has expired", ex.getMessage());

        assertTrue(expiredTx.isExpired());
        verify(transactionRepository, times(1)).save(expiredTx);
        verify(paymentProvider, never()).processPayment(any());
    }

    @Test
    @DisplayName("Payment pending tồn tại nhưng chưa có transaction thì tạo mới và gọi provider")
    void shouldCreateTransactionWhenPendingPaymentHasNoTransaction() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderId(104L)
                .amount(BigDecimal.valueOf(500000))
                .currency(Currency.VND)
                .method(PaymentMethodType.VNPAY)
                .build();

        Payment existingPayment = new Payment(4L, 104L, Money.vnd(500000), PaymentMethodType.VNPAY);

        when(paymentRepository.findByOrderIdAndStatus(104L, PaymentStatus.PENDING)).thenReturn(Optional.of(existingPayment));
        when(transactionRepository.findPendingChargeByPaymentId(4L)).thenReturn(Optional.empty());

        com.thanh.payment.application.port.out.ProviderCheckoutResult mockResult = com.thanh.payment.application.port.out.ProviderCheckoutResult.builder()
                .checkoutUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html")
                .transactionId("tx-456")
                .build();
        when(paymentProvider.processPayment(any())).thenReturn(mockResult);

        PaymentResponse response = paymentService.createPayment(command);

        assertNotNull(response);
        assertEquals(4L, response.getId());
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(paymentProvider, times(1)).processPayment(any());
    }

    @Test
    @DisplayName("VN-08: Success callback cập nhật Payment và Transaction trong cùng database transaction")
    void shouldProcessTransactionSuccessForBothPaymentAndTransaction() {
        Long txId = 555L;
        Payment payment = new Payment(10L, 201L, Money.vnd(150000), PaymentMethodType.VNPAY);
        Transaction transaction = new Transaction(txId, 10L, Money.vnd(150000), TransactionType.CHARGE, OffsetDateTime.now().plusMinutes(15));

        when(transactionRepository.findById(txId)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment));

        ProcessTransactionCommand command = ProcessTransactionCommand.builder()
                .transactionId(String.valueOf(txId))
                .providerTransactionId("VNPAY_TR_999")
                .success(true)
                .build();

        paymentService.processTransaction(command);

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        assertEquals("VNPAY_TR_999", transaction.getProviderTransactionId());

        verify(paymentRepository, times(1)).save(payment);
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("VN-08: Failure callback cập nhật Payment và Transaction thất bại kèm lý do")
    void shouldProcessTransactionFailureForBothPaymentAndTransaction() {
        Long txId = 666L;
        Payment payment = new Payment(11L, 202L, Money.vnd(180000), PaymentMethodType.VNPAY);
        Transaction transaction = new Transaction(txId, 11L, Money.vnd(180000), TransactionType.CHARGE, OffsetDateTime.now().plusMinutes(15));

        when(transactionRepository.findById(txId)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(11L)).thenReturn(Optional.of(payment));

        ProcessTransactionCommand command = ProcessTransactionCommand.builder()
                .transactionId(String.valueOf(txId))
                .providerTransactionId("VNPAY_TR_888")
                .failureReason("Khách hàng hủy giao dịch")
                .success(false)
                .build();

        paymentService.processTransaction(command);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(TransactionStatus.FAILED, transaction.getStatus());
        assertEquals("Khách hàng hủy giao dịch", transaction.getFailureReason());

        verify(paymentRepository, times(1)).save(payment);
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("verifyWebhook ném lỗi SecurityException khi chữ ký không hợp lệ")
    void shouldThrowSecurityExceptionWhenSignatureInvalid() {
        PaymentVerificationResult invalidResult = PaymentVerificationResult.builder()
                .isSignatureValid(false)
                .build();

        when(paymentProvider.verify(any())).thenReturn(invalidResult);

        assertThrows(SecurityException.class, () ->
                paymentService.verifyWebhook(PaymentMethodType.VNPAY, new Object())
        );

        verify(paymentRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ném IllegalStateException khi không tìm thấy Transaction tương ứng")
    void shouldThrowExceptionWhenTransactionNotFound() {
        ProcessTransactionCommand command = ProcessTransactionCommand.builder()
                .transactionId("999999")
                .success(true)
                .build();

        when(transactionRepository.findById(999999L)).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                paymentService.processTransaction(command)
        );
        assertTrue(ex.getMessage().contains("Không tìm thấy Transaction"));

        verify(paymentRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ném IllegalStateException khi không tìm thấy Payment tương ứng với Transaction")
    void shouldThrowExceptionWhenPaymentNotFoundForTransaction() {
        Long txId = 777L;
        Transaction transaction = new Transaction(txId, 99L, Money.vnd(100000), TransactionType.CHARGE, OffsetDateTime.now().plusMinutes(15));

        when(transactionRepository.findById(txId)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        ProcessTransactionCommand command = ProcessTransactionCommand.builder()
                .transactionId(String.valueOf(txId))
                .success(true)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                paymentService.processTransaction(command)
        );
        assertTrue(ex.getMessage().contains("Không tìm thấy Payment tương ứng với Transaction"));

        verify(paymentRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tra cứu thành công qua orderId khi transactionId không phải số")
    void shouldProcessTransactionFallbackByOrderId() {
        Payment payment = new Payment(20L, 501L, Money.vnd(200000), PaymentMethodType.VNPAY);
        Transaction transaction = new Transaction(888L, 20L, Money.vnd(200000), TransactionType.CHARGE, OffsetDateTime.now().plusMinutes(15));

        when(transactionRepository.findByProviderTransactionId("NON_NUMERIC_ID")).thenReturn(Optional.empty());
        when(paymentRepository.findByOrderIdAndStatus(501L, PaymentStatus.PENDING)).thenReturn(Optional.of(payment));
        when(transactionRepository.findPendingChargeByPaymentId(20L)).thenReturn(Optional.of(transaction));
        when(paymentRepository.findById(20L)).thenReturn(Optional.of(payment));

        ProcessTransactionCommand command = ProcessTransactionCommand.builder()
                .orderId(501L)
                .transactionId("NON_NUMERIC_ID")
                .providerTransactionId("PROVIDER_NO_1")
                .success(true)
                .build();

        paymentService.processTransaction(command);

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(TransactionStatus.SUCCESS, transaction.getStatus());
        assertEquals("PROVIDER_NO_1", transaction.getProviderTransactionId());

        verify(paymentRepository, times(1)).save(payment);
        verify(transactionRepository, times(1)).save(transaction);
    }
}
