package com.thanh.payment.application.service;

import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.command.ProcessTransactionCommand;
import com.thanh.payment.application.command.RefundCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.dto.PaymentVerificationResult;
import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.WebhookVerifier;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.exception.ActivePaymentExistsException;
import com.thanh.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Dịch vụ trung tâm của luồng thanh toán.
 *
 *   - createPayment: tạo Payment + Transaction và dựng URL VNPay (an toàn khi bị gọi lặp nhờ idempotency + khoá).
 *   - processTransaction / verifyWebhook: đường xử lý callback CŨ dành cho MoMo/bank (VNPay đã có VnpayIpnService riêng).
 *   - refund: chưa triển khai.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final PaymentCreator paymentCreator;
    private final PaymentProviderFactory paymentProviderFactory;
    private final WebhookVerifierFactory webhookVerifierFactory;
    private final Clock clock;

    /**
     * Tạo thanh toán VNPay. KHÔNG có @Transactional: Redis nằm ngoài transaction DB, phần ghi DB nằm ở PaymentCreator.
     *
     * Các bước:
     *   1. Validate đầu vào, kiểm tra provider đã bật, tính keyHash và requestHash.
     *   2. IdempotencyGuard xin giữ key trong Redis (nguyên tử): trùng thì báo "đang xử lý" / conflict,
     *      đã xong thì trả lại kết quả cũ, Redis sập thì 503.
     *   3. PaymentCreator ghi DB trong một transaction (INSERT Payment, Transaction, idempotency_keys; ký URL VNPay).
     *      Chống trùng ở DB nhờ unique: vi phạm thì replay hoặc báo order đang có payment active.
     *   4. Thành công thì đánh dấu key COMPLETED (24h); thoát bằng exception thì try-with-resources nhả key.
     */
    public PaymentResponse createPayment(CreatePaymentCommand command) {
        // Bước 1: kiểm tra đầu vào (độc lập với Bean Validation ở controller) rồi chọn provider đang bật.
        validateCreateCommand(command);
        PaymentProvider provider = getEnabledProvider(command.getMethod());
        // Chỉ băm key rồi dùng hash để tra/lưu: không lưu Idempotency-Key gốc. requestHash đại diện "ý định" của request để phát hiện cùng key mà khác nội dung.
        String keyHash = sha256(command.getIdempotencyKey());
        String requestHash = requestHash(command);
        // Thời gian lấy từ Clock được tiêm vào (không gọi giờ hệ thống trực tiếp) để test với giờ cố định.
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Bước 2: xin key. Ném ApiException nếu trùng/conflict/Redis sập.
        IdempotencyGuard.Acquisition acquisition = idempotencyGuard.acquire(keyHash, requestHash);
        if (acquisition instanceof IdempotencyGuard.AlreadyCompleted done) {
            return paymentCreator.replay(done.paymentId());
        }
        // Bước 3-4: try-with-resources đảm bảo key luôn được nhả nếu chưa complete.
        try (IdempotencyTicket ticket = ((IdempotencyGuard.Acquired) acquisition).ticket()) {
            PaymentResponse response = createOrRecover(command, provider, keyHash, requestHash, now);
            ticket.complete(response.getPaymentId());
            return response;
        }
    }

    // Ghi DB; chống trùng dựa vào unique của DB (đã được repository dịch thành exception domain):
    //   - key đã có trong DB (vd Redis mất dữ liệu) => trả lại kết quả cũ, 409 nếu khác nội dung, hoặc tạo lại nếu key đã hết hạn
    //   - order đã có payment đang hoạt động       => 409 PAYMENT_ALREADY_PENDING
    private PaymentResponse createOrRecover(CreatePaymentCommand command, PaymentProvider provider,
                                            String keyHash, String requestHash, OffsetDateTime now) {
        try {
            return paymentCreator.create(command, provider, keyHash, requestHash, now);
        } catch (DuplicateIdempotencyKeyException duplicate) {
            try {
                return paymentCreator.replayOrRestart(command, provider, keyHash, requestHash, now);
            } catch (ActivePaymentExistsException active) {
                throw orderAlreadyPending();
            }
        } catch (ActivePaymentExistsException active) {
            throw orderAlreadyPending();
        }
    }

    private ApiException orderAlreadyPending() {
        return new ApiException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_PENDING",
                "The order already has an active payment");
    }

    // Hiện chỉ VNPAY được hỗ trợ; phương thức khác bị từ chối trước khi chạm DB. Provider chưa bật cấu hình => 400 PAYMENT_METHOD_NOT_ENABLED.
    private PaymentProvider getEnabledProvider(PaymentMethodType method) {
        try {
            return paymentProviderFactory.getEnabledProvider(method);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_NOT_ENABLED", exception.getMessage());
        }
    }

    // Kiểm tra đầu vào chung và ràng buộc số tiền của từng cổng. Chạy trước mọi truy vấn DB để request sai bị loại sớm, không giữ khoá.
    private void validateCreateCommand(CreatePaymentCommand command) {
        if (command.getCurrency() != Currency.VND) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REQUEST", "Only VND is supported");
        }
        BigDecimal amount = command.getAmount();
        // VND không có phần lẻ. VNPay yêu cầu tối thiểu 5.000đ; cổng khác chỉ cần dương (adapter kiểm tra mức tối thiểu riêng).
        BigDecimal minimum = command.getMethod() == PaymentMethodType.VNPAY ? BigDecimal.valueOf(5_000) : BigDecimal.ONE;
        if (amount == null || amount.stripTrailingZeros().scale() > 0 || amount.compareTo(minimum) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Amount must be a whole VND amount of at least " + minimum.toPlainString());
        }
        // VNPay quy định vnp_Amount = số tiền x 100 và tối đa 12 chữ số.
        if (command.getMethod() == PaymentMethodType.VNPAY
                && amount.toBigIntegerExact().multiply(BigInteger.valueOf(100)).toString().length() > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "VNPay amount exceeds protocol limits");
        }
        if (command.getIdempotencyKey() == null
                // Key phải là 8-128 ký tự ASCII hiển thị được (không khoảng trắng).
                || !command.getIdempotencyKey().matches("[\\x21-\\x7E]{8,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 8 to 128 visible ASCII characters");
        }
        if (command.getClientIp() == null || command.getClientIp().isBlank()
                || command.getClientIp().length() > 45) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_IP", "Client IP is required");
        }
    }

    // Băm "ý định" của request: orderId|amount|currency|method (amount đã bỏ số 0 thừa nên 150000 và 150000.00 cho cùng hash). Dùng để phân biệt replay hợp lệ với dùng sai key.
    private String requestHash(CreatePaymentCommand command) {
        String canonical = command.getOrderId() + "|"
                + command.getAmount().stripTrailingZeros().toPlainString() + "|"
                + command.getCurrency() + "|" + command.getMethod();
        return sha256(canonical);
    }

    // SHA-256 dạng hex (64 ký tự), khớp độ dài cột key_hash/request_hash.
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    // Đường xử lý callback CŨ cho MoMo/bank: đánh dấu Payment/Transaction theo kết quả đã verify.
    // KHÔNG kiểm tra số tiền và không khoá hàng, nên không dùng cho VNPay (đã có VnpayIpnProcessor).
    @Transactional
    public void processTransaction(ProcessTransactionCommand command) {
        Transaction transaction = findTransaction(command);
        Payment payment = paymentRepository.findById(transaction.getPaymentId())
                .orElseThrow(
                        () -> new IllegalStateException("Payment not found for transaction " + transaction.getId()));
        if (command.isSuccess()) {
            payment.markCompleted();
            transaction.markSuccess(command.getProviderTransactionId() != null
                    ? command.getProviderTransactionId()
                    : command.getTransactionId());
        } else {
            payment.markFailed();
            transaction.markFailed(command.getProviderTransactionId(), null, command.getFailureReason());
        }
        paymentRepository.save(payment);
        transactionRepository.save(transaction);
    }

    private Transaction findTransaction(ProcessTransactionCommand command) {
        if (command.getTransactionId() != null) {
            try {
                return transactionRepository.findById(Long.valueOf(command.getTransactionId()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Transaction not found: " + command.getTransactionId()));
            } catch (NumberFormatException ignored) {
                Optional<Transaction> byProvider = transactionRepository
                        .findByProviderTransactionId(command.getTransactionId());
                if (byProvider.isPresent()) {
                    return byProvider.get();
                }
            }
        }
        Payment payment = paymentRepository.findByOrderIdAndStatus(command.getOrderId(), PaymentStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("Pending payment not found"));
        return transactionRepository.findPendingChargeByPaymentId(payment.getId())
                .orElseThrow(() -> new IllegalStateException("Pending charge not found"));
    }

    // Đường cũ: verify chữ ký bằng WebhookVerifier của provider rồi gọi processTransaction.
    // Hiện không với tới được vì MoMo/bank chưa bật; cần thay bằng processor riêng như VnpayIpnProcessor trước khi bật.
    @Transactional
    public PaymentVerificationResult verifyWebhook(PaymentMethodType type, Object payload) {
        // Provider chưa bật thì không có endpoint này: 404 thay vì lỗi chung.
        if (!paymentProviderFactory.getProvider(type).isEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROVIDER_DISABLED", "Payment provider is not enabled: " + type);
        }
        WebhookVerifier verifier = webhookVerifierFactory.getVerifier(type);
        PaymentVerificationResult result = verifier.verify(payload);
        if (!result.isSignatureValid()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid signature from provider: " + type);
        }
        processTransaction(ProcessTransactionCommand.builder()
                .orderId(result.getOrderId())
                .transactionId(result.getTransactionId())
                .providerTransactionId(result.getProviderTransactionId())
                .failureReason(result.getFailureReason())
                .success(result.isSuccess())
                .build());
        return result;
    }

    public void refund(RefundCommand command) {
        // Refund is intentionally outside the VNPay P0 slice.
    }
}
