package com.thanh.payment.adapter.out.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
// Body JSON VNPay trả về cho QueryDR. ignoreUnknown=true để VNPay thêm trường mới không làm hỏng việc đọc.
record VnpayQueryDrResponse(
        @JsonProperty("vnp_ResponseId") String responseId,
        @JsonProperty("vnp_Command") String command,
        @JsonProperty("vnp_ResponseCode") String responseCode,
        @JsonProperty("vnp_Message") String message,
        @JsonProperty("vnp_TmnCode") String tmnCode,
        @JsonProperty("vnp_TxnRef") String txnRef,
        @JsonProperty("vnp_Amount") String amount,
        @JsonProperty("vnp_BankCode") String bankCode,
        @JsonProperty("vnp_PayDate") String payDate,
        @JsonProperty("vnp_TransactionNo") String transactionNo,
        @JsonProperty("vnp_TransactionType") String transactionType,
        @JsonProperty("vnp_TransactionStatus") String transactionStatus,
        @JsonProperty("vnp_OrderInfo") String orderInfo,
        @JsonProperty("vnp_PromotionCode") String promotionCode,
        @JsonProperty("vnp_PromotionAmount") String promotionAmount,
        @JsonProperty("vnp_SecureHash") String secureHash
) {
}
