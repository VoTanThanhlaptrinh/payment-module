package com.thanh.payment.application.dto;

// Kết quả đã chuẩn hoá của một lần QueryDR. attemptCode (vd TXN_00, TXN_01, TRANSPORT_ERROR, INVALID_SIGNATURE) được lưu vào last_reconciliation_code để biết lần thử gần nhất ra sao.
// payDate (vnp_PayDate) và bankCode (vnp_BankCode) chỉ có khi VNPay trả về chi tiết giao dịch; có thể null.
public record VnpayQueryResult(
        VnpayQueryDecision decision,
        String attemptCode,
        String transactionStatus,
        String providerTransactionId,
        String description,
        String payDate,
        String bankCode
) {
    public VnpayQueryResult(VnpayQueryDecision decision, String attemptCode, String transactionStatus,
            String providerTransactionId, String description) {
        this(decision, attemptCode, transactionStatus, providerTransactionId, description, null, null);
    }

    public static VnpayQueryResult retry(String attemptCode, String description) {
        return new VnpayQueryResult(VnpayQueryDecision.RETRY, attemptCode, null, null, description);
    }
}
