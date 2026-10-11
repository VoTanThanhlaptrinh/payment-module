package com.thanh.payment.domain.model;

import com.thanh.payment.domain.valueobject.Money;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Entity Transaction.
 * <p>
 * Đại diện cho từng lần thực hiện giao dịch thanh toán hoặc hoàn tiền cụ thể.
 * Sử dụng ID kiểu Long sinh tự động bằng TSID.
 * Mỗi lần khách đi thanh toán là một Transaction CHARGE. Với VNPay, id của Transaction chính là vnp_TxnRef
 * (KHÔNG phải orderId) vì một order có thể thử thanh toán nhiều lần, mỗi lần cần mã tham chiếu riêng.
 *
 * Vòng đời trạng thái:
 *   PENDING --IPN/QueryDR thành công--> SUCCESS
 *   PENDING --mã từ chối--------------> FAILED
 *   PENDING --mã 11-------------------> EXPIRED
 *   PENDING --mã 07-------------------> REVIEW_REQUIRED
 * Lưu ý: isTerminal() = "khác PENDING", nên REVIEW_REQUIRED cũng được coi là kết thúc (IPN đến sau sẽ nhận RspCode 02).
 *
 * Nhóm cột reconciliation_* phục vụ đối soát khi mất IPN: scheduler chọn các CHARGE còn PENDING có
 * next_reconciliation_at <= now, gọi QueryDR, rồi cập nhật số lần thử; hết số lần tối đa thì đánh dấu exhausted.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    // Khoá chính TSID, sinh ở ứng dụng trước khi INSERT. Giá trị này được dùng làm vnp_TxnRef gửi cho VNPay.
    @Id
    @Tsid
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // Khoá ngoại tới payments.id (thuộc Payment nào). Không đổi sau khi tạo.
    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    // Mã giao dịch phía VNPay (vnp_TransactionNo). Chỉ có sau khi VNPay trả kết quả; có unique index một phần nên một mã VNPay không thể gắn vào 2 giao dịch.
    @Column(name = "provider_transaction_id", length = 100, nullable = true)
    private String providerTransactionId;

    // Mã kết quả VNPay trả về (vnp_ResponseCode, vd 00, 51, 11...), phục vụ tra cứu/điều tra.
    @Column(name = "provider_response_code", length = 20)
    private String providerResponseCode;

    // URL thanh toán đã ký gửi cho khách (có chứa chữ ký). Chỉ lưu DB, tuyệt đối không ghi log URL đầy đủ.
    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    // Số tiền của lần thanh toán này (cột amount, currency). IPN so sánh vnp_Amount với giá trị này nhân 100.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", precision = 18, scale = 4, nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 10, nullable = false))
    })
    private Money amount;

    // Loại giao dịch: CHARGE (thu tiền) hoặc REFUND (hoàn tiền).
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private TransactionType type;

    // Trạng thái giao dịch (xem sơ đồ ở đầu class).
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private TransactionStatus status;

    // Khoá lạc quan: UPDATE kèm "where version=?" để tránh hai luồng ghi đè lẫn nhau (bổ sung cho khoá hàng FOR UPDATE ở IPN/đối soát).
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Lý do thất bại/hết hạn/cần duyệt, do hệ thống ghi khi markFailed/markExpired/markReviewRequired.
    @Column(name = "failure_reason", columnDefinition = "TEXT", nullable = true)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Hạn thanh toán (createdAt + expiry-minutes). Cũng là mốc để scheduler bắt đầu đối soát nếu chưa có kết quả.
    @Column(name = "expired_at", nullable = false)
    private OffsetDateTime expiredAt;

    // Thời điểm khách trả tiền. LƯU Ý: hiện chưa có đoạn code nào gán giá trị cho cột này (markSuccess chỉ ghi completedAt).
    @Column(name = "paid_at", nullable = true)
    private OffsetDateTime paidAt;

    // Thời điểm giao dịch đạt trạng thái kết thúc (SUCCESS/FAILED/EXPIRED/REVIEW_REQUIRED).
    @Column(name = "completed_at", nullable = true)
    private OffsetDateTime completedAt;

    // Số lần đối soát (QueryDR) đã thử. Tăng ở recordReconciliationAttempt().
    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    // Lần đối soát kế tiếp. Ban đầu = expiredAt; sau mỗi lần thử = now + interval; NULL khi đã kết thúc hoặc hết lượt.
    @Column(name = "next_reconciliation_at")
    private OffsetDateTime nextReconciliationAt;

    // Thời điểm lần đối soát gần nhất.
    @Column(name = "last_reconciliation_at")
    private OffsetDateTime lastReconciliationAt;

    // Mã kết quả lần đối soát gần nhất, vd TXN_00 (thành công), TXN_01 (chưa có kết quả), TRANSPORT_ERROR, INVALID_SIGNATURE, RESPONSE_MISMATCH.
    @Column(name = "last_reconciliation_code", length = 20)
    private String lastReconciliationCode;

    // true khi đã dùng hết số lần đối soát mà vẫn chưa rõ kết quả: payment giữ PENDING để xử lý thủ công, scheduler không nhặt nữa.
    @Column(name = "reconciliation_exhausted", nullable = false)
    private boolean reconciliationExhausted;

    /**
     * Constructor khởi tạo Transaction mới.
     */
    public Transaction(Long paymentId, Money amount, TransactionType type, OffsetDateTime expiredAt) {
        if (paymentId == null) {
            throw new IllegalArgumentException("PaymentId cannot be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("TransactionType cannot be null");
        }
        if (expiredAt == null) {
            throw new IllegalArgumentException("ExpiredAt cannot be null");
        }
        this.paymentId = paymentId;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.expiredAt = expiredAt;
        // Lên lịch đối soát đầu tiên đúng vào lúc hết hạn: tới lúc đó mà chưa có IPN thì mới cần hỏi lại VNPay.
        this.nextReconciliationAt = expiredAt;
    }

    /**
     * Constructor có truyền ID (nếu cần gán TSID từ bên ngoài).
     */
    public Transaction(Long id, Long paymentId, Money amount, TransactionType type, OffsetDateTime expiredAt) {
        this(paymentId, amount, type, expiredAt);
        this.id = id;
    }

    /**
     * Factory method tạo Transaction thu tiền (CHARGE).
     */
    public static Transaction createCharge(Long paymentId, Money amount, OffsetDateTime expiredAt) {
        return new Transaction(paymentId, amount, TransactionType.CHARGE, expiredAt);
    }

    // Biến thể nhận createdAt từ bên ngoài (do Clock của service cấp), để createdAt khớp với vnp_CreateDate gửi VNPay và test dùng được Clock cố định.
    public static Transaction createCharge(Long paymentId, Money amount, OffsetDateTime createdAt,
            OffsetDateTime expiredAt) {
        Transaction transaction = new Transaction(paymentId, amount, TransactionType.CHARGE, expiredAt);
        transaction.createdAt = createdAt;
        return transaction;
    }

    /**
     * Factory method tạo Transaction hoàn tiền (REFUND).
     */
    public static Transaction createRefund(Long paymentId, Money amount, OffsetDateTime expiredAt) {
        return new Transaction(paymentId, amount, TransactionType.REFUND, expiredAt);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
        if (this.status == null) {
            this.status = TransactionStatus.PENDING;
        }
    }

    // --- Domain Business Methods ---

    /**
     * Đánh dấu giao dịch thành công khi nhận callback / IPN từ cổng thanh toán.
     *
     * @param providerTransactionId Mã giao dịch trả về từ đối tác (VNPay, MoMo,...)
     */
    public void markSuccess(String providerTransactionId) {
        markSuccess(providerTransactionId, "00");
    }

    /**
     * Giao dịch thành công. Idempotent (gọi lại khi đã SUCCESS thì bỏ qua); chỉ cho phép từ PENDING.
     * Ghi mã VNPay, thời điểm hoàn tất và xoá lịch đối soát (không cần hỏi lại nữa).
     */
    public void markSuccess(String providerTransactionId, String responseCode) {
        if (this.status == TransactionStatus.SUCCESS) {
            return;
        }
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Cannot mark transaction as SUCCESS from status: %s", this.status));
        }
        this.status = TransactionStatus.SUCCESS;
        this.providerTransactionId = normalizeProviderTransactionId(providerTransactionId);
        this.providerResponseCode = responseCode;
        this.completedAt = OffsetDateTime.now();
        this.nextReconciliationAt = null;
    }

    /**
     * Đánh dấu giao dịch thất bại.
     *
     * @param failureReason Lý do thất bại từ đối tác hoặc hệ thống.
     */
    public void markFailed(String failureReason) {
        markFailed(null, null, failureReason);
    }

    // Giao dịch thất bại. Không được hạ giao dịch đã SUCCESS xuống FAILED. Xoá lịch đối soát.
    public void markFailed(String providerTransactionId, String responseCode, String failureReason) {
        if (this.status == TransactionStatus.FAILED) {
            return;
        }
        if (this.status == TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Cannot mark as FAILED an already successful transaction");
        }
        this.status = TransactionStatus.FAILED;
        this.providerTransactionId = normalizeProviderTransactionId(providerTransactionId);
        this.providerResponseCode = responseCode;
        this.failureReason = failureReason;
        this.completedAt = OffsetDateTime.now();
        this.nextReconciliationAt = null;
    }

    /**
     * Đánh dấu giao dịch đã hết hạn timeout.
     */
    public void markExpired() {
        markExpired(null, null, null);
    }

    // Giao dịch hết hạn. Không được chuyển giao dịch đã SUCCESS thành EXPIRED. Xoá lịch đối soát.
    public void markExpired(String providerTransactionId, String responseCode, String failureReason) {
        if (this.status == TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Cannot expire an already successful transaction");
        }
        if (this.status == TransactionStatus.EXPIRED) {
            return;
        }
        this.status = TransactionStatus.EXPIRED;
        this.providerTransactionId = normalizeProviderTransactionId(providerTransactionId);
        this.providerResponseCode = responseCode;
        this.failureReason = failureReason;
        this.completedAt = OffsetDateTime.now();
        this.nextReconciliationAt = null;
    }

    // VNPay đánh dấu đáng ngờ: dừng ở REVIEW_REQUIRED chờ người duyệt, không tự quyết. Chỉ từ PENDING.
    public void markReviewRequired(String providerTransactionId, String responseCode, String reason) {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Cannot review transaction in status: " + this.status);
        }
        this.status = TransactionStatus.REVIEW_REQUIRED;
        this.providerTransactionId = normalizeProviderTransactionId(providerTransactionId);
        this.providerResponseCode = responseCode;
        this.failureReason = reason;
        this.completedAt = OffsetDateTime.now();
        this.nextReconciliationAt = null;
    }

    /**
     * Gắn URL thanh toán đã ký vào giao dịch ngay sau khi VnpayAdapter dựng xong.
     * Đồng bộ lại expiredAt theo giá trị provider trả và đặt lịch đối soát đầu tiên (nếu chưa đối soát lần nào).
     */
    public void attachCheckout(String checkoutUrl, OffsetDateTime expiresAt) {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Checkout can only be attached to a pending transaction");
        }
        if (checkoutUrl == null || checkoutUrl.isBlank()) {
            throw new IllegalArgumentException("Checkout URL cannot be blank");
        }
        this.checkoutUrl = checkoutUrl;
        if (expiresAt != null) {
            this.expiredAt = expiresAt;
            if (this.reconciliationAttempts == 0) {
                this.nextReconciliationAt = expiresAt;
            }
        }
    }

    /**
     * Ghi nhận MỘT lần đối soát (được gọi trước khi áp dụng kết quả, kể cả khi kết quả là thành công):
     * tăng attempts, lưu thời điểm + mã kết quả; nếu đạt maxAttempts thì bật exhausted và xoá lịch,
     * ngược lại đặt lịch lần tiếp theo.
     */
    public void recordReconciliationAttempt(
            OffsetDateTime attemptedAt,
            String responseCode,
            OffsetDateTime nextAttemptAt,
            int maxAttempts) {
        if (attemptedAt == null) {
            throw new IllegalArgumentException("Reconciliation attempt time cannot be null");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("Max reconciliation attempts must be positive");
        }
        this.reconciliationAttempts++;
        this.lastReconciliationAt = attemptedAt;
        this.lastReconciliationCode = responseCode;
        this.reconciliationExhausted = this.reconciliationAttempts >= maxAttempts;
        this.nextReconciliationAt = this.reconciliationExhausted ? null : nextAttemptAt;
    }

    // VNPay trả "0" hoặc rỗng khi chưa có mã giao dịch: chuẩn hoá về null để không vi phạm unique index trên provider_transaction_id.
    private String normalizeProviderTransactionId(String value) {
        return value == null || value.isBlank() || "0".equals(value) ? null : value;
    }

    public boolean isExpired() {
        if (this.status == TransactionStatus.EXPIRED) {
            return true;
        }
        return this.status == TransactionStatus.PENDING &&
                expiredAt != null &&
                OffsetDateTime.now().isAfter(expiredAt);
    }

    public boolean isPending() {
        return this.status == TransactionStatus.PENDING;
    }

    public boolean isSuccess() {
        return this.status == TransactionStatus.SUCCESS;
    }

    public boolean isFailed() {
        return this.status == TransactionStatus.FAILED;
    }

    // Đã kết thúc = KHÁC PENDING (kể cả REVIEW_REQUIRED). IPN/đối soát dùng hàm này để không xử lý lại/ghi đè.
    public boolean isTerminal() {
        return this.status != TransactionStatus.PENDING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Transaction that = (Transaction) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
