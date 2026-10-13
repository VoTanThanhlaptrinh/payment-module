package com.thanh.payment.infrastructure.provider.payos;

import com.thanh.payment.shared.exception.ApiException;
import com.thanh.payment.application.provider.ProviderCheckoutRequest;
import com.thanh.payment.application.provider.ProviderCheckoutResult;
import com.thanh.payment.application.provider.ProviderCheckoutStatus;
import com.thanh.payment.application.provider.ProviderOrderCodeGenerator;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PayosAdapterTest {

    private static final long ORDER_CODE = 1700000000123L;
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-11T10:00:00+07:00");
    private static final OffsetDateTime EXPIRES = CREATED.plusMinutes(15);
    // Vector HMAC-SHA256 độc lập cho cùng dữ liệu (xem PayosSignatureServiceTest).
    private static final String SIGNATURE = "c8a8af46238849cf8a08a8db29b429853cbb341b66ded0ee9256a21eea0dc352";

    private MockRestServiceServer server;
    private PayosProperties properties;
    private PayosAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new PayosProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setApiKey("api-key");
        properties.setChecksumKey("test-checksum-key");
        properties.setReturnUrl("https://merchant.test/return");
        properties.setCancelUrl("https://merchant.test/cancel");
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getApiUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        ProviderOrderCodeGenerator codes = () -> ORDER_CODE;
        adapter = new PayosAdapter(properties, new PayosSignatureService(properties), codes, builder.build());
    }

    @Test
    void shouldCreateSignedPaymentLinkAndReturnCheckoutUrl() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-client-id", "client-id"))
                .andExpect(header("x-api-key", "api-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderCode").value(ORDER_CODE))
                .andExpect(jsonPath("$.amount").value(10_000))
                .andExpect(jsonPath("$.description").value("DH101"))
                .andExpect(jsonPath("$.returnUrl").value("https://merchant.test/return"))
                .andExpect(jsonPath("$.cancelUrl").value("https://merchant.test/cancel"))
                .andExpect(jsonPath("$.expiredAt").value(EXPIRES.toEpochSecond()))
                .andExpect(jsonPath("$.signature").value(SIGNATURE))
                .andRespond(withSuccess(success(ORDER_CODE, "https://pay.payos.vn/web/abc"), MediaType.APPLICATION_JSON));

        ProviderCheckoutResult result = adapter.processPayment(request(10_000, 101L));

        assertEquals("https://pay.payos.vn/web/abc", result.getCheckoutUrl());
        assertEquals("55", result.getTransactionId());
        assertEquals(ORDER_CODE, result.getProviderOrderCode());
        assertEquals(EXPIRES, result.getExpiresAt());
        assertEquals(ProviderCheckoutStatus.PENDING, result.getStatus());
        server.verify();
    }

    @Test
    void shouldTruncateDescriptionToConfiguredLength() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andExpect(jsonPath("$.description").value("DH1234567"))
                .andRespond(withSuccess(success(ORDER_CODE, "https://pay.payos.vn/web/abc"), MediaType.APPLICATION_JSON));

        adapter.processPayment(request(10_000, 1234567890123L));

        server.verify();
    }

    @Test
    void shouldSendPartnerCodeOnlyWhenConfigured() {
        properties.setPartnerCode("PARTNER1");
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andExpect(header("x-partner-code", "PARTNER1"))
                .andRespond(withSuccess(success(ORDER_CODE, "https://pay.payos.vn/web/abc"), MediaType.APPLICATION_JSON));

        adapter.processPayment(request(10_000, 101L));

        server.verify();
    }

    @Test
    void shouldMapProviderRejectionToBadGatewayWithoutLeakingDetails() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"401\",\"desc\":\"secret-api-key-leak\"}"));

        ApiException exception = assertThrows(ApiException.class, () -> adapter.processPayment(request(10_000, 101L)));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("PROVIDER_ERROR", exception.getCode());
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    void shouldRejectUnsuccessfulResultCode() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess("{\"code\":\"231\",\"desc\":\"Don thanh toan da ton tai\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        ApiException exception = assertThrows(ApiException.class, () -> adapter.processPayment(request(10_000, 101L)));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void shouldRejectResponseForDifferentOrderCode() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess(success(ORDER_CODE + 1, "https://pay.payos.vn/web/abc"), MediaType.APPLICATION_JSON));

        assertEquals("PROVIDER_ERROR", assertThrows(ApiException.class,
                () -> adapter.processPayment(request(10_000, 101L))).getCode());
    }

    @Test
    void shouldRejectNonHttpsCheckoutUrl() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(withSuccess(success(ORDER_CODE, "http://evil.test/pay"), MediaType.APPLICATION_JSON));

        assertEquals("PROVIDER_ERROR", assertThrows(ApiException.class,
                () -> adapter.processPayment(request(10_000, 101L))).getCode());
    }

    @Test
    void shouldReportTransportFailureAsServiceUnavailableWithRetryAfter() {
        server.expect(requestTo("https://api-merchant.payos.vn/v2/payment-requests"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset");
                });

        ApiException exception = assertThrows(ApiException.class, () -> adapter.processPayment(request(10_000, 101L)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("PROVIDER_UNAVAILABLE", exception.getCode());
        assertEquals(5, exception.getRetryAfterSeconds());
    }

    @Test
    void shouldRejectAmountBelowConfiguredMinimumWithoutCallingPayos() {
        ApiException exception = assertThrows(ApiException.class, () -> adapter.processPayment(request(1_999, 101L)));

        assertEquals("INVALID_AMOUNT", exception.getCode());
        server.verify();
    }

    @Test
    void shouldReportSupportedTypeAndEnabledState() {
        assertEquals(PaymentMethodType.PAYOS, adapter.getSupportedType());
        assertTrue(adapter.isEnabled());
        assertEquals(15, adapter.getExpiryMinutes());
        properties.setEnabled(false);
        assertFalse(adapter.isEnabled());
        assertThrows(IllegalStateException.class, () -> adapter.processPayment(request(10_000, 101L)));
    }

    private ProviderCheckoutRequest request(long amount, long orderId) {
        return ProviderCheckoutRequest.builder()
                .paymentId(1L).transactionId(55L).orderId(orderId)
                .amount(Money.vnd(amount)).createdAt(CREATED).expiresAt(EXPIRES).clientIp("127.0.0.1")
                .build();
    }

    private String success(long orderCode, String checkoutUrl) {
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"bin\":\"970422\",\"accountNumber\":\"1\","
                + "\"amount\":10000,\"description\":\"DH101\",\"orderCode\":" + orderCode + ",\"currency\":\"VND\","
                + "\"paymentLinkId\":\"124c33293c934a85be5b7f8761a27a07\",\"status\":\"PENDING\","
                + "\"checkoutUrl\":\"" + checkoutUrl + "\",\"qrCode\":\"000201\"},\"signature\":\"abc\"}";
    }
}
