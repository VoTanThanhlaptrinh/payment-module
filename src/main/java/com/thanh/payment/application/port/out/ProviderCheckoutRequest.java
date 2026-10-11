package com.thanh.payment.application.port.out;

import com.thanh.payment.domain.valueobject.Money;
import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

@Getter
@Builder
public class ProviderCheckoutRequest {
    private final Long paymentId;
    // Id của Transaction: chính là vnp_TxnRef gửi cho VNPay.
    private final Long transactionId;
    private final Long orderId;
    private final Money amount;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;
    // IP của khách (vnp_IpAddr), VNPay yêu cầu để chống gian lận.
    private final String clientIp;
}
