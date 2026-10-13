package com.thanh.payment.domain.model;

import com.thanh.payment.domain.enums.PaymentMethodType;
import com.thanh.payment.domain.enums.PaymentStatus;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Entity Payment (Aggregate Root).
 * <p>
 * Đại diện cho một giao dịch thanh toán gắn liền với một đơn hàng (Order).
 * Sử dụng ID kiểu Long sinh tự động bằng TSID.
 * Vòng đời (trạng thái) của Payment trong luồng VNPay:
 *   PENDING --IPN/QueryDR thành công--> COMPLETED --hoàn tiền--> REFUNDED / PARTIALLY_REFUNDED
 *   PENDING --IPN mã từ chối---------> FAILED
 *   PENDING --IPN mã 11 (hết hạn)----> EXPIRED
 *   PENDING --IPN mã 07 (nghi ngờ)---> REVIEW_REQUIRED (chờ người duyệt, vẫn coi là "đang hoạt động")
 * Mọi chuyển trạng thái đều đi qua các hàm markXxx() bên dưới; chuyển sai sẽ ném IllegalStateException.
 *
 * Quan hệ: 1 Payment (1 đơn hàng) có nhiều Transaction (mỗi lần khách thử thanh toán là một Transaction CHARGE).
 * Bảng payments có unique index một phần uq_payments_order_id_active: mỗi order chỉ được có 1 payment
 * đang PENDING/REVIEW_REQUIRED, đây là chốt chặn cuối cùng chống tạo trùng khi 2 request đua nhau.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    // Khoá chính. @Tsid: ứng dụng tự sinh ID (TSID) TRƯỚC khi INSERT, nên ngay sau saveAndFlush đã có id để dùng tiếp.
    @Id
    @Tsid
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // Mã đơn hàng của merchant. Không bao giờ đổi sau khi tạo (updatable = false).
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    // Value object Money (số tiền + loại tiền) được "trải phẳng" thành 2 cột total_amount, currency. VNPay chỉ nhận VND, số nguyên.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "total_amount", precision = 18, scale = 4, nullable = false)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 10, nullable = false))
    })
    private Money totalAmount;

    // Phương thức thanh toán (VNPAY, MOMO, ...). Lưu dạng chữ để đọc được trực tiếp trong DB.
    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 30, nullable = false)
    private PaymentMethodType method;

    // Trạng thái hiện tại của Payment (xem sơ đồ vòng đời ở đầu class).
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private PaymentStatus status;

    // Khoá lạc quan (optimistic lock): mỗi lần UPDATE, Hibernate chạy "... where id=? and version=?" và tăng version. Hai tiến trình cùng sửa một dòng thì tiến trình sau bị từ chối thay vì ghi đè.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    // Chạy ngay trước INSERT đầu tiên: điền createdAt/updatedAt và đảm bảo status mặc định là PENDING.
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

    // Chạy ngay trước mỗi UPDATE: cập nhật updatedAt.
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // --- Domain Business Methods (Aggregate Root) ---

    /**
     * Thanh toán thành công (IPN 00/00 hoặc QueryDR báo SUCCESS).
     * Idempotent: gọi lại khi đã COMPLETED thì không làm gì. Chỉ cho phép đi từ PENDING.
     */
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

    /**
     * Thanh toán thất bại (ngân hàng từ chối, khách huỷ...). Idempotent.
     * Không được "hạ" một payment đã COMPLETED xuống FAILED (tiền đã thu thật).
     */
    public void markFailed() {
        if (this.status == PaymentStatus.FAILED) {
            return;
        }
        if (this.status == PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Cannot fail an already completed payment");
        }
        this.status = PaymentStatus.FAILED;
    }

    // Hết hạn thanh toán (VNPay mã 11). Chỉ hợp lệ khi đang PENDING.
    public void markExpired() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot expire payment in status: " + this.status);
        }
        this.status = PaymentStatus.EXPIRED;
    }

    // VNPay nghi ngờ giao dịch (mã 07): không tự động coi là thành công/thất bại, chuyển sang chờ người duyệt. Chỉ từ PENDING.
    public void markReviewRequired() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot review payment in status: " + this.status);
        }
        this.status = PaymentStatus.REVIEW_REQUIRED;
    }

    // Hoàn tiền toàn bộ. Chỉ hợp lệ với payment đã COMPLETED (hoặc đã hoàn tiền một phần). Luồng refund chưa triển khai.
    public void markRefunded() {
        if (this.status != PaymentStatus.COMPLETED && this.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    String.format("Cannot refund payment in status: %s", this.status)
            );
        }
        this.status = PaymentStatus.REFUNDED;
    }

    // Hoàn tiền một phần. Điều kiện giống markRefunded(). Luồng refund chưa triển khai.
    public void markPartiallyRefunded() {
        if (this.status != PaymentStatus.COMPLETED && this.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    String.format("Cannot partially refund payment in status: %s", this.status)
            );
        }
        this.status = PaymentStatus.PARTIALLY_REFUNDED;
    }

    // Dùng ở IPN/đối soát: chỉ payment còn PENDING mới được xử lý kết quả, ngược lại coi là đã xử lý xong (RspCode 02).
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
}
