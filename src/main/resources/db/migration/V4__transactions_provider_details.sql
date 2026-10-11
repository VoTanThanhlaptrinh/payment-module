-- Chi tiết thanh toán VNPay trả về khi thành công, dùng để đối chiếu với sao kê.
ALTER TABLE transactions
    ADD COLUMN provider_bank_code VARCHAR(20),
    ADD COLUMN provider_card_type VARCHAR(20),
    ADD COLUMN provider_bank_tran_no VARCHAR(255);
