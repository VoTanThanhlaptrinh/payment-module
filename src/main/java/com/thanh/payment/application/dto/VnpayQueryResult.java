package com.thanh.payment.application.dto;

// Kết quả đã chuẩn hoá của một lần QueryDR. attemptCode (vd TXN_00, TXN_01, TRANSPORT_ERROR, INVALID_SIGNATURE) được lưu vào last_reconciliation_code để biết lần thử gần nhất ra sao.
public record VnpayQueryResult(
        VnpayQueryDecision decision,
        String attemptCode,
        String transactionStatus,
        String providerTransactionId,
        String description
) {
    public static VnpayQueryResult retry(String attemptCode, String description) {
        return new VnpayQueryResult(VnpayQueryDecision.RETRY, attemptCode, null, null, description);
    }
}
