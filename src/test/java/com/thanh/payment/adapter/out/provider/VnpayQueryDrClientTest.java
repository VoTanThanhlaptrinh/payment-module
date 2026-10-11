package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.dto.VnpayQueryDecision;
import com.thanh.payment.application.dto.VnpayQueryResult;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionType;
import com.thanh.payment.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VnpayQueryDrClientTest {

    private VnpayProperties properties;
    private VnpaySignatureService signatures;
    private VnpayQueryDrCodec codec;
    private VnpayQueryDrClient client;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        properties = new VnpayProperties();
        properties.setVersion("2.1.0");
        properties.setTmnCode("TEST0001");
        properties.setHashSecret("test-secret");
        properties.setMerchantIp("127.0.0.1");
        properties.setQueryApiUrl("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        properties.setQueryConnectTimeout(Duration.ofSeconds(3));
        properties.setQueryReadTimeout(Duration.ofSeconds(5));
        signatures = new VnpaySignatureService(properties);
        codec = new VnpayQueryDrCodec(properties, signatures);
        client = new VnpayQueryDrClient(
                properties,
                codec,
                Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC),
                RestClient.builder());
        transaction = new Transaction(
                12345L,
                1L,
                Money.vnd(5_000),
                TransactionType.CHARGE,
                OffsetDateTime.parse("2026-10-04T09:00:00Z"));
    }

    @Test
    void shouldMapVerifiedTransactionStatuses() {
        assertEquals(VnpayQueryDecision.SUCCESS, map("00").decision());
        assertEquals(VnpayQueryDecision.PENDING, map("01").decision());
        assertEquals(VnpayQueryDecision.FAILED, map("02").decision());
        assertEquals(VnpayQueryDecision.REVIEW_REQUIRED, map("04").decision());
        assertEquals(VnpayQueryDecision.REVIEW_REQUIRED, map("07").decision());
        assertEquals(VnpayQueryDecision.RETRY, map("88").decision());
    }

    @Test
    void shouldRejectSignedResponseThatDoesNotMatchAmount() {
        VnpayQueryDrResponse response = signedResponse("00", "500100", "00");

        VnpayQueryResult result = client.validateAndMap(transaction, response);

        assertEquals(VnpayQueryDecision.RETRY, result.decision());
        assertEquals("RESPONSE_MISMATCH", result.attemptCode());
    }

    @Test
    void shouldRejectInvalidResponseSignature() {
        VnpayQueryDrResponse response = response("00", "500000", "00", "0".repeat(128));

        VnpayQueryResult result = client.validateAndMap(transaction, response);

        assertEquals(VnpayQueryDecision.RETRY, result.decision());
        assertEquals("INVALID_SIGNATURE", result.attemptCode());
    }

    @Test
    void shouldMapSignedNotFoundResponseToNotFound() {
        VnpayQueryResult result = client.validateAndMap(transaction, signedNotFound("TEST0001", "12345"));

        assertEquals(VnpayQueryDecision.NOT_FOUND, result.decision());
        assertEquals("API_91", result.attemptCode());
    }

    @Test
    void shouldNotTrustNotFoundResponseForAnotherTransaction() {
        VnpayQueryResult result = client.validateAndMap(transaction, signedNotFound("TEST0001", "99999"));

        assertEquals(VnpayQueryDecision.RETRY, result.decision());
        assertEquals("RESPONSE_MISMATCH", result.attemptCode());
    }

    @Test
    void shouldNotTrustNotFoundResponseWithInvalidSignature() {
        VnpayQueryDrResponse forged = new VnpayQueryDrResponse(
                "RESP123", "querydr", "91", "Not found", "TEST0001", "12345",
                "", "", "", "", "", "", "", "", "", "0".repeat(128));

        VnpayQueryResult result = client.validateAndMap(transaction, forged);

        assertEquals(VnpayQueryDecision.RETRY, result.decision());
        assertEquals("INVALID_SIGNATURE", result.attemptCode());
    }

    private VnpayQueryDrResponse signedNotFound(String tmnCode, String txnRef) {
        VnpayQueryDrResponse unsigned = new VnpayQueryDrResponse(
                "RESP123", "querydr", "91", "Not found", tmnCode, txnRef,
                "", "", "", "", "", "", "", "", "", null);
        return new VnpayQueryDrResponse(
                "RESP123", "querydr", "91", "Not found", tmnCode, txnRef,
                "", "", "", "", "", "", "", "", "",
                signatures.signRaw(codec.responseSignatureData(unsigned)));
    }

    private VnpayQueryResult map(String transactionStatus) {
        return client.validateAndMap(transaction, signedResponse("00", "500000", transactionStatus));
    }

    private VnpayQueryDrResponse signedResponse(String responseCode, String amount, String transactionStatus) {
        VnpayQueryDrResponse unsigned = response(responseCode, amount, transactionStatus, null);
        return response(responseCode, amount, transactionStatus,
                signatures.signRaw(codec.responseSignatureData(unsigned)));
    }

    private VnpayQueryDrResponse response(
            String responseCode,
            String amount,
            String transactionStatus,
            String secureHash) {
        return new VnpayQueryDrResponse(
                "RESP123", "querydr", responseCode, "Result", "TEST0001", "12345",
                amount, "NCB", "20261004100500", "98765", "01", transactionStatus,
                "Thanh toan", "", "", secureHash);
    }
}
