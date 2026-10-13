package com.thanh.payment.application.provider;

import com.thanh.payment.domain.model.Transaction;

public interface VnpayTransactionQuery {
    // Hỏi VNPay (QueryDR) trạng thái thật của một giao dịch. Cài đặt: VnpayQueryDrClient.
    VnpayQueryResult query(Transaction transaction);
}
