-- =============================================================================
-- Bảng: payments
-- Mô tả: Lưu trữ thông tin các giao dịch thanh toán (Aggregate Root: Payment)
-- Hệ quản trị CSDL: PostgreSQL
-- =============================================================================

CREATE TABLE IF NOT EXISTS payments (
    id           BIGINT                      NOT NULL,
    order_id     BIGINT                      NOT NULL,
    total_amount NUMERIC(18, 4)              NOT NULL,
    currency     VARCHAR(10)                 NOT NULL,
    method       VARCHAR(30)                 NOT NULL,
    status       VARCHAR(30)                 NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE    NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE    NOT NULL,

    CONSTRAINT pk_payments PRIMARY KEY (id)
);

-- =============================================================================
-- Constraint: Chỉ cho phép duy nhất 1 giao dịch PENDING cho mỗi order_id
-- Sử dụng Partial Unique Index trong PostgreSQL.
-- Điều này đảm bảo:
-- 1. Một đơn hàng (order_id) chỉ có tối đa 1 payment ở trạng thái 'PENDING'.
-- 2. Không giới hạn số lượng payment ở các trạng thái khác (FAILED, COMPLETED, REFUNDED)
--    khi đơn hàng thực hiện thanh toán lại hoặc hoàn tiền.
-- =============================================================================
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_order_id_pending
ON payments (order_id)
WHERE status = 'PENDING';

-- Index hỗ trợ tìm kiếm nhanh theo order_id
CREATE INDEX IF NOT EXISTS idx_payments_order_id
ON payments (order_id);
