package com.thanh.payment.infrastructure.provider.vnpay;

import com.fasterxml.jackson.annotation.JsonProperty;

// Body JSON gửi lên API QueryDR của VNPay. vnp_SecureHash là HMAC trên các trường nối bằng "|" (xem VnpayQueryDrCodec).
record VnpayQueryDrRequest(
        @JsonProperty("vnp_RequestId") String requestId,
        @JsonProperty("vnp_Version") String version,
        @JsonProperty("vnp_Command") String command,
        @JsonProperty("vnp_TmnCode") String tmnCode,
        @JsonProperty("vnp_TxnRef") String txnRef,
        @JsonProperty("vnp_OrderInfo") String orderInfo,
        @JsonProperty("vnp_TransactionDate") String transactionDate,
        @JsonProperty("vnp_CreateDate") String createDate,
        @JsonProperty("vnp_IpAddr") String ipAddress,
        @JsonProperty("vnp_SecureHash") String secureHash
) {
}
