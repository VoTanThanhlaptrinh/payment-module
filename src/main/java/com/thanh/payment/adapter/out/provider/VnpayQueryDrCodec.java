package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;

// Mã hoá/giải mã QueryDR. Request và response đều được ký bằng HMAC-SHA512 trên chuỗi các trường nối bằng "|" theo THỨ TỰ cố định của VNPay (khác với query string của URL thanh toán).
@Component
@RequiredArgsConstructor
public class VnpayQueryDrCodec {

    private final VnpayProperties properties;
    private final VnpaySignatureService signatures;

    // Dựng request QueryDR đã ký: gồm requestId (duy nhất mỗi lần gọi), mã giao dịch (vnp_TxnRef), ngày giao dịch và IP của merchant.
    VnpayQueryDrRequest signedRequest(
            String requestId,
            String txnRef,
            String transactionDate,
            String createDate,
            String orderInfo) {
        String signatureData = join(
                requestId,
                properties.getVersion(),
                "querydr",
                properties.getTmnCode(),
                txnRef,
                transactionDate,
                createDate,
                properties.getMerchantIp(),
                orderInfo);
        return new VnpayQueryDrRequest(
                requestId,
                properties.getVersion(),
                "querydr",
                properties.getTmnCode(),
                txnRef,
                orderInfo,
                transactionDate,
                createDate,
                properties.getMerchantIp(),
                signatures.signRaw(signatureData));
    }

    // Chỉ tin response QueryDR khi chữ ký hợp lệ. Sai chữ ký => bỏ qua kết quả và coi là lần thử lỗi.
    boolean verifyResponse(VnpayQueryDrResponse response) {
        return response != null && signatures.verifyRaw(responseSignatureData(response), response.secureHash());
    }

    // Chuỗi được ký của response: 15 trường theo đúng thứ tự VNPay quy định, trường null thay bằng chuỗi rỗng.
    String responseSignatureData(VnpayQueryDrResponse response) {
        return join(
                response.responseId(),
                response.command(),
                response.responseCode(),
                response.message(),
                response.tmnCode(),
                response.txnRef(),
                response.amount(),
                response.bankCode(),
                response.payDate(),
                response.transactionNo(),
                response.transactionType(),
                response.transactionStatus(),
                response.orderInfo(),
                response.promotionCode(),
                response.promotionAmount());
    }

    private String join(String... values) {
        StringJoiner joiner = new StringJoiner("|");
        for (String value : values) {
            joiner.add(value == null ? "" : value);
        }
        return joiner.toString();
    }
}
