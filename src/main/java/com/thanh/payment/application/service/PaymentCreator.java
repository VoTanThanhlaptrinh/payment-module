package com.thanh.payment.application.service;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.command.CreatePaymentCommand;
import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.mapper.PaymentMapper;
import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.domain.model.IdempotencyKey;
import com.thanh.payment.domain.model.IdempotencyState;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.repository.IdempotencyKeyRepository;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Phần ghi DB của luồng tạo thanh toán, mỗi hàm là MỘT transaction.
 * <p>
 * Tách thành bean riêng để PaymentService (không có transaction) gọi qua proxy và để Redis nằm ngoài transaction DB.
 * Chống trùng ở đây dựa vào unique của DB: INSERT luôn, không SELECT trước.
 *   - uq_idempotency_scope_key   : một Idempotency-Key chỉ có một dòng.
 *   - uq_payments_order_id_active: một order chỉ có một payment PENDING/REVIEW_REQUIRED.
 * Vi phạm unique làm hàm ném DataIntegrityViolationException và rollback toàn bộ; PaymentService quyết định xử lý tiếp.
 */
@Service
@RequiredArgsConstructor
public class PaymentCreator {

    private final PaymentRepository paymentRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final VnpayProperties vnpayProperties;

    /**
     * Tạo mới: Payment(PENDING) + Transaction CHARGE(PENDING) + URL VNPay đã ký + idempotency_keys(COMPLETED).
     */
    @Transactional(timeoutString = "${payment.idempotency.max-processing-time-seconds:10}")
    public PaymentResponse create(CreatePaymentCommand command, PaymentProvider provider,
                                  String keyHash, String requestHash, OffsetDateTime now) {
        return persist(command, provider, IdempotencyKey.start(keyHash, requestHash, now.plusHours(24)), now);
    }

    /**
     * Gọi khi INSERT idempotency_keys vi phạm unique, nghĩa là DB đã có key này (vd Redis mất dữ liệu):
     *   - key còn hạn  => replay (cùng nội dung) hoặc 409 IDEMPOTENCY_CONFLICT (khác nội dung)
     *   - key hết hạn  => dùng lại dòng cũ bằng restart() rồi tạo mới
     */
    @Transactional(timeoutString = "${payment.idempotency.max-processing-time-seconds:10}")
    public PaymentResponse replayOrRestart(CreatePaymentCommand command, PaymentProvider provider,
                                           String keyHash, String requestHash, OffsetDateTime now) {
        IdempotencyKey key = idempotencyKeyRepository.find(IdempotencyKey.CREATE_PAYMENT_SCOPE, keyHash)
                .orElseThrow(() -> new IllegalStateException("Idempotency key violated a unique constraint but was not found"));
        if (!key.isExpired(now)) {
            return replay(key, requestHash);
        }
        key.restart(requestHash, now.plusHours(24));
        return persist(command, provider, key, now);
    }

    /**
     * Dựng lại response của một payment đã tạo (Redis báo COMPLETED): không ghi gì vào DB.
     */
    @Transactional(readOnly = true)
    public PaymentResponse replay(long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Idempotent payment no longer exists"));
        return toReplayResponse(payment);
    }

    private PaymentResponse replay(IdempotencyKey key, String requestHash) {
        if (!key.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used with a different request");
        }
        if (key.getState() != IdempotencyState.COMPLETED || key.getPaymentId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this idempotency key is still in progress", 1);
        }
        Payment payment = paymentRepository.findById(key.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Idempotent payment no longer exists"));
        return toReplayResponse(payment);
    }

    // Response y như lần đầu, lấy checkoutUrl/hạn từ Transaction CHARGE mới nhất đã lưu.
    private PaymentResponse toReplayResponse(Payment payment) {
        Transaction transaction = transactionRepository.findLatestChargeByPaymentId(payment.getId())
                .orElseThrow(() -> new IllegalStateException("Idempotent charge transaction no longer exists"));
        return PaymentMapper.toResponseDTO(payment, transaction);
    }

    private PaymentResponse persist(CreatePaymentCommand command, PaymentProvider provider,
                                    IdempotencyKey key, OffsetDateTime now) {
        // Bước 1: ghi/khoá key TRƯỚC (IN_PROGRESS, chưa có payment_id). Nếu một transaction khác đang giữ cùng key chưa commit,
        // Postgres bắt câu này chờ rồi báo trùng, nên request lặp lại luôn nhận kết quả cũ thay vì đụng unique của order.
        idempotencyKeyRepository.saveAndFlush(key);

        // Money chuẩn hoá số tiền (scale 4) và loại tiền.
        Money amount = Money.of(command.getAmount(), command.getCurrency());
        // saveAndFlush: INSERT ngay để id TSID có sẵn và uq_payments_order_id_active (nếu bị vi phạm) nổ tại đây.
        Payment payment = paymentRepository.saveAndFlush(
                Payment.create(command.getOrderId(), amount, command.getMethod()));
        if (payment.getId() == null) {
            throw new IllegalStateException("Payment ID was not generated before transaction creation");
        }

        // Hạn thanh toán (mặc định 15 phút): gửi VNPay làm vnp_ExpireDate và cũng là mốc bắt đầu đối soát nếu mất IPN.
        OffsetDateTime expiresAt = now.plusMinutes(vnpayProperties.getExpiryMinutes());
        // Tạo lần thử thanh toán (CHARGE, PENDING). Id của Transaction này sẽ là vnp_TxnRef.
        Transaction transaction = transactionRepository.saveAndFlush(
                Transaction.createCharge(payment.getId(), amount, now, expiresAt));
        if (transaction.getId() == null) {
            throw new IllegalStateException("Transaction ID was not generated before checkout creation");
        }

        // Nhờ adapter dựng phiên thanh toán. Với VNPay chỉ là ký URL cục bộ (không gọi mạng) nên chấp nhận được khi đang giữ DB transaction.
        ProviderCheckoutResult checkout = provider.processPayment(ProviderCheckoutRequest.builder()
                .paymentId(payment.getId())
                .transactionId(transaction.getId())
                .orderId(payment.getOrderId())
                .amount(amount)
                .createdAt(now)
                .expiresAt(expiresAt)
                .clientIp(command.getClientIp())
                .build());

        // Lưu URL đã ký vào Transaction (phục vụ replay idempotency).
        transaction.attachCheckout(checkout.getCheckoutUrl(), checkout.getExpiresAt());
        transactionRepository.save(transaction);
        // Bước cuối: key chuyển COMPLETED và gắn payment_id (UPDATE trong cùng transaction).
        key.complete(payment.getId());
        idempotencyKeyRepository.save(key);
        return PaymentMapper.toResponseDTO(payment, checkout);
    }
}
