package com.thanh.payment.infrastructure.cache;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Thời gian sống của bản ghi idempotency trong Redis (payment.idempotency.*).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "payment.idempotency")
public class IdempotencyStoreProperties {

    // IN_PROGRESS: đủ dài cho một lần tạo thanh toán; nếu instance chết giữa chừng thì key tự hết hạn và client retry được.
    private Duration inProgressTtl = Duration.ofSeconds(30);

    // COMPLETED: trong khoảng này request lặp lại được trả kết quả cũ ngay từ Redis.
    private Duration completedTtl = Duration.ofHours(24);

    // Thời gian tối đa của transaction DB tạo thanh toán (giây). PaymentCreator dùng làm timeout của @Transactional.
    @Min(1)
    private int maxProcessingTimeSeconds = 10;

    // Key IN_PROGRESS phải sống lâu hơn nhiều so với transaction dài nhất, nếu không key Redis hết hạn khi request đầu còn đang chạy
    // và request song song lọt qua Redis (DB vẫn chặn, nhưng hành vi khó đoán). Cấu hình sai thì app không khởi động.
    @AssertTrue(message = "payment.idempotency.in-progress-ttl must be at least twice max-processing-time-seconds")
    public boolean isInProgressTtlCoveringProcessingTime() {
        return inProgressTtl != null && inProgressTtl.toSeconds() >= 2L * maxProcessingTimeSeconds;
    }
}
