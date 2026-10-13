package com.thanh.payment.api.dto.webhook;

import java.util.Map;

// Kết quả bước verify IPN: signatureValid (chữ ký đúng), malformed (thiếu tham số bắt buộc/tham số trùng), parameters (các tham số vnp_*).
public record VnpayIpnData(
        boolean signatureValid,
        boolean malformed,
        Map<String, String> parameters
) {
    public String get(String name) {
        return parameters.get(name);
    }
}
