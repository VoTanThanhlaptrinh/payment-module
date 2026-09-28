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
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @Tsid
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "provider_transaction_id", length = 100, nullable = true)
    private String providerTransactionId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", precision = 18, scale = 4, nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 10, nullable = false))
    })
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private TransactionStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT", nullable = true)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expired_at", nullable = false)
    private OffsetDateTime expiredAt;

    @Column(name = "completed_at", nullable = true)
    private OffsetDateTime completedAt;

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
        if (this.status == TransactionStatus.SUCCESS) {
            return;
        }
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Cannot mark transaction as SUCCESS from status: %s", this.status)
            );
        }
        this.status = TransactionStatus.SUCCESS;
        this.providerTransactionId = providerTransactionId;
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * Đánh dấu giao dịch thất bại.
     *
     * @param failureReason Lý do thất bại từ đối tác hoặc hệ thống.
     */
    public void markFailed(String failureReason) {
        if (this.status == TransactionStatus.FAILED) {
            return;
        }
        if (this.status == TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Cannot mark as FAILED an already successful transaction");
        }
        this.status = TransactionStatus.FAILED;
        this.failureReason = failureReason;
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * Đánh dấu giao dịch đã hết hạn timeout.
     */
    public void markExpired() {
        if (this.status == TransactionStatus.SUCCESS) {
            throw new IllegalStateException("Cannot expire an already successful transaction");
        }
        this.status = TransactionStatus.EXPIRED;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
