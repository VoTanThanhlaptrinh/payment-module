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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Entity Payment (Aggregate Root).
 * <p>
 * Đại diện cho một giao dịch thanh toán gắn liền với một đơn hàng (Order).
 * Sử dụng ID kiểu Long sinh tự động bằng TSID.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @Tsid
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "total_amount", precision = 18, scale = 4, nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 10, nullable = false))
    })
    private Money totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 30, nullable = false)
    private PaymentMethodType method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Constructor phục vụ khởi tạo Payment mới.
     */
    public Payment(Long orderId, Money totalAmount, PaymentMethodType method) {
        if (orderId == null) {
            throw new IllegalArgumentException("OrderId cannot be null");
        }
        if (totalAmount == null) {
            throw new IllegalArgumentException("TotalAmount cannot be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("PaymentMethodType cannot be null");
        }
        this.orderId = orderId;
        this.totalAmount = totalAmount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Constructor có truyền ID (nếu cần gán TSID từ bên ngoài).
     */
    public Payment(Long id, Long orderId, Money totalAmount, PaymentMethodType method) {
        this(orderId, totalAmount, method);
        this.id = id;
    }

    /**
     * Factory method tạo Payment mới ở trạng thái PENDING.
     */
    public static Payment create(Long orderId, Money totalAmount, PaymentMethodType method) {
        return new Payment(orderId, totalAmount, method);
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.status == null) {
            this.status = PaymentStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // --- Domain Business Methods (Aggregate Root) ---

    public void markCompleted() {
        if (this.status == PaymentStatus.COMPLETED) {
            return;
        }
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Cannot complete payment in status: %s", this.status)
            );
        }
        this.status = PaymentStatus.COMPLETED;
    }

    public void markFailed() {
        if (this.status == PaymentStatus.FAILED) {
            return;
        }
        if (this.status == PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Cannot fail an already completed payment");
        }
        this.status = PaymentStatus.FAILED;
    }

    public void markRefunded() {
        if (this.status != PaymentStatus.COMPLETED && this.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    String.format("Cannot refund payment in status: %s", this.status)
            );
        }
        this.status = PaymentStatus.REFUNDED;
    }

    public void markPartiallyRefunded() {
        if (this.status != PaymentStatus.COMPLETED && this.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    String.format("Cannot partially refund payment in status: %s", this.status)
            );
        }
        this.status = PaymentStatus.PARTIALLY_REFUNDED;
    }

    public boolean isPending() {
        return this.status == PaymentStatus.PENDING;
    }

    public boolean isCompleted() {
        return this.status == PaymentStatus.COMPLETED;
    }

    public boolean isFailed() {
        return this.status == PaymentStatus.FAILED;
    }

    public boolean isRefunded() {
        return this.status == PaymentStatus.REFUNDED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Payment payment = (Payment) o;
        return id != null && Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
