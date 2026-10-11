package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.port.out.ProviderCheckoutRequest;
import com.thanh.payment.application.port.out.ProviderCheckoutResult;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VnpayAdapterTest {

    private VnpayAdapter adapter;

    @BeforeEach
    void setUp() {
        VnpayProperties properties = new VnpayProperties();
        properties.setEnabled(true);
        properties.setPaymentUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setReturnUrl("https://merchant.test/payments/vnpay/return");
        properties.setTmnCode("TEST0001");
        properties.setHashSecret("test-secret");
        properties.setVersion("2.1.0");
        properties.setExpiryMinutes(15);
        adapter = new VnpayAdapter(properties, new VnpaySignatureService(properties));
    }

    @Test
    void shouldBuildGoldenSignedCheckoutUrl() {
        ProviderCheckoutResult result = adapter.processPayment(ProviderCheckoutRequest.builder()
                .paymentId(1L)
                .transactionId(12345L)
                .orderId(99L)
                .amount(Money.vnd(5_000))
                .createdAt(OffsetDateTime.parse("2026-10-04T03:00:00Z"))
                .expiresAt(OffsetDateTime.parse("2026-10-04T03:15:00Z"))
                .clientIp("127.0.0.1")
                .build());

        assertEquals("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"
                + "vnp_Amount=500000&vnp_Command=pay&vnp_CreateDate=20261004100000&vnp_CurrCode=VND"
                + "&vnp_ExpireDate=20261004101500&vnp_IpAddr=127.0.0.1&vnp_Locale=vn"
                + "&vnp_OrderInfo=Thanh+toan+don+hang+99&vnp_OrderType=other"
                + "&vnp_ReturnUrl=https%3A%2F%2Fmerchant.test%2Fpayments%2Fvnpay%2Freturn"
                + "&vnp_TmnCode=TEST0001&vnp_TxnRef=12345&vnp_Version=2.1.0"
                + "&vnp_SecureHash=a39854e509f080ba3a88a4562ccb85c801af3de99f4c3a958303d8727a221d69"
                + "a1c3a92c5e957917513f6b7ccaf71f2dd6c99e2d44a93aa6ea18e89e48a8a169",
                result.getCheckoutUrl());
        assertFalse(result.getCheckoutUrl().contains("vnp_SecureHashType"));
    }
}
