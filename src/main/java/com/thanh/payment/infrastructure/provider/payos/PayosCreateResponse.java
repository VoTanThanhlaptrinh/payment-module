package com.thanh.payment.infrastructure.provider.payos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Body payOS trả về khi tạo link: code "00" là thành công; data chứa checkoutUrl, paymentLinkId, orderCode. ignoreUnknown để payOS thêm trường không làm hỏng việc đọc.
@JsonIgnoreProperties(ignoreUnknown = true)
record PayosCreateResponse(
        String code,
        String desc,
        Data data,
        String signature
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(
            Long orderCode,
            String paymentLinkId,
            String status,
            String checkoutUrl
    ) {
    }
}
