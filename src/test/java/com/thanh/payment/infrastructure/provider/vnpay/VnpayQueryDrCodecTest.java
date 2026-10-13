package com.thanh.payment.infrastructure.provider.vnpay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VnpayQueryDrCodecTest {

    private VnpayQueryDrCodec codec;
    private VnpaySignatureService signatures;

    @BeforeEach
    void setUp() {
        VnpayProperties properties = new VnpayProperties();
        properties.setVersion("2.1.0");
        properties.setTmnCode("TEST0001");
        properties.setHashSecret("test-secret");
        properties.setMerchantIp("127.0.0.1");
        signatures = new VnpaySignatureService(properties);
        codec = new VnpayQueryDrCodec(properties, signatures);
    }

    @Test
    void shouldBuildGoldenQueryDrRequestSignature() {
        VnpayQueryDrRequest request = codec.signedRequest(
                "12345678901234567890123456789012",
                "12345",
                "20261004100000",
                "20261004102000",
                "Truy van giao dich 12345");

        assertEquals("3eaadaf5cc48a1e1f8d5af87deb8db007f95d0da177549ccde3b565e242ef9d01"
                        + "dc1439f839c3d315ba64ba86ba407d8568ccedd45148ce07a9324475696afb8",
                request.secureHash());
    }

    @Test
    void shouldVerifyGoldenResponseAndRejectTampering() {
        VnpayQueryDrResponse unsigned = response("500000", null);
        String hash = signatures.signRaw(codec.responseSignatureData(unsigned));
        VnpayQueryDrResponse signed = response("500000", hash);

        assertEquals("006699a26cdfb8d8c828463ef5099ccaa916d89ba9e22360ebeb3beca1b174a1f"
                        + "af2031b4bf52f636ae12d6e16075d39302b19705280ada15217fdb4837e0b11",
                hash);
        assertTrue(codec.verifyResponse(signed));
        assertFalse(codec.verifyResponse(response("500100", hash)));
    }

    @Test
    void shouldSerializeQueryDrWireNames() throws Exception {
        VnpayQueryDrRequest request = codec.signedRequest(
                "12345678901234567890123456789012",
                "12345",
                "20261004100000",
                "20261004102000",
                "Truy van giao dich 12345");

        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(request);

        assertTrue(json.contains("\"vnp_RequestId\":\"12345678901234567890123456789012\""));
        assertTrue(json.contains("\"vnp_Command\":\"querydr\""));
        assertTrue(json.contains("\"vnp_SecureHash\":"));

        VnpayQueryDrResponse response = mapper.readValue("""
                {"vnp_ResponseId":"RESP123","vnp_Command":"querydr","vnp_ResponseCode":"00",
                 "vnp_Message":"Success","vnp_TmnCode":"TEST0001","vnp_TxnRef":"12345",
                 "vnp_Amount":"500000","vnp_TransactionNo":"98765",
                 "vnp_TransactionType":"01","vnp_TransactionStatus":"00",
                 "vnp_SecureHash":"%s"}
                """.formatted("0".repeat(128)), VnpayQueryDrResponse.class);
        assertEquals("RESP123", response.responseId());
        assertEquals("00", response.transactionStatus());
    }

    private VnpayQueryDrResponse response(String amount, String hash) {
        return new VnpayQueryDrResponse(
                "RESP123", "querydr", "00", "Success", "TEST0001", "12345",
                amount, "NCB", "20261004100500", "98765", "01", "00",
                "Thanh toan", "", "", hash);
    }
}
