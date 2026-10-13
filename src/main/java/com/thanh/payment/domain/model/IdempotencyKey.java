package com.thanh.payment.domain.model;

import com.thanh.payment.domain.enums.IdempotencyState;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
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

/**
 * Bản ghi idempotency cho API tạo thanh toán (bảng idempotency_keys).
 *
 * Mục đích: client có thể gửi lại cùng một request (timeout, mất mạng...) mà không sinh ra payment thứ hai.
 *   - key_hash    : SHA-256 của header Idempotency-Key (không lưu key gốc).
 *   - request_hash: SHA-256 của "orderId|amount|currency|method". Cùng key mà khác hash => 409 IDEMPOTENCY_CONFLICT.
 *   - state       : IN_PROGRESS (đang xử lý) -> COMPLETED (đã tạo xong, có payment_id để replay).
 *   - expires_at  : hết hạn sau 24h; key hết hạn thì được "restart" thay vì replay.
 * (scope, key_hash) là unique nên cùng một key không thể có hai dòng.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    // Phạm vi của key: để sau này các API khác (vd hoàn tiền) dùng chung bảng mà không đụng key của nhau.
    public static final String CREATE_PAYMENT_SCOPE = "CREATE_PAYMENT";

    // Khoá chính TSID, sinh ở ứng dụng.
    @Id
    @Tsid
    private Long id;

    @Column(nullable = false, length = 50)
    private String scope;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyState state;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // Tạo key mới ở trạng thái IN_PROGRESS, chưa có payment_id (chỉ có sau khi payment được tạo xong).
    public static IdempotencyKey start(String keyHash, String requestHash, OffsetDateTime expiresAt) {
        IdempotencyKey key = new IdempotencyKey();
        key.scope = CREATE_PAYMENT_SCOPE;
        key.keyHash = keyHash;
        key.requestHash = requestHash;
        key.state = IdempotencyState.IN_PROGRESS;
        key.expiresAt = expiresAt;
        return key;
    }

    // Dùng lại dòng cũ khi key đã HẾT HẠN: đặt lại request_hash mới, xoá payment_id cũ và quay về IN_PROGRESS.
    public void restart(String requestHash, OffsetDateTime expiresAt) {
        this.requestHash = requestHash;
        this.paymentId = null;
        this.state = IdempotencyState.IN_PROGRESS;
        this.expiresAt = expiresAt;
    }

    // Đánh dấu đã xử lý xong và gắn payment_id. Từ lúc này, request lặp lại sẽ được replay đúng payment này.
    public void complete(Long paymentId) {
        if (paymentId == null) {
            throw new IllegalArgumentException("Payment ID is required");
        }
        this.paymentId = paymentId;
        this.state = IdempotencyState.COMPLETED;
    }

    // now do Clock của service cấp (không dùng giờ hệ thống trực tiếp) để test được với thời gian cố định.
    public boolean isExpired(OffsetDateTime now) {
        return expiresAt.isBefore(now);
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
