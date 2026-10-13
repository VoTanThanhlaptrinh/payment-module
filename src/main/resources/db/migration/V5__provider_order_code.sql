-- Mã đơn dạng số mà một số cổng (payOS orderCode) yêu cầu; Transaction.id (TSID) quá lớn so với giới hạn 2^53-1 của họ.
ALTER TABLE transactions
    ADD COLUMN provider_order_code BIGINT;

CREATE UNIQUE INDEX uq_transactions_provider_order_code
    ON transactions (provider_order_code)
    WHERE provider_order_code IS NOT NULL;

-- Sequence bắt đầu từ epoch-mili-giây lúc chạy migration (~1,7e12, còn rất xa 2^53). Cổng không bao giờ chấp nhận lại một mã đã dùng,
-- nên nếu database bị dựng lại thì sequence mới vẫn lớn hơn mọi mã đã gửi trước đó thay vì quay về 1 và đụng mã cũ.
DO $$
BEGIN
    EXECUTE format('CREATE SEQUENCE provider_order_code_seq START WITH %s',
                   (extract(epoch FROM clock_timestamp()) * 1000)::bigint);
END
$$;
