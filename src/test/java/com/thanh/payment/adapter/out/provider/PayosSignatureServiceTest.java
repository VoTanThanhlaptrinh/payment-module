package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.PayosProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayosSignatureServiceTest {

    // Vector tính độc lập bằng HMAC-SHA256 (Python hmac) với key "test-checksum-key".
    private static final String EXPECTED =
            "c8a8af46238849cf8a08a8db29b429853cbb341b66ded0ee9256a21eea0dc352";

    @Test
    void shouldSignPaymentRequestInAlphabeticalFieldOrderWithoutEncoding() {
        PayosSignatureService service = service("test-checksum-key");

        String signature = service.signPaymentRequest(10_000, "https://merchant.test/cancel", "DH101",
                1700000000123L, "https://merchant.test/return");

        assertEquals(EXPECTED, signature);
    }

    @Test
    void shouldVerifyWithConstantTimeComparisonIgnoringCase() {
        PayosSignatureService service = service("test-checksum-key");
        String data = "amount=10000&cancelUrl=https://merchant.test/cancel&description=DH101"
                + "&orderCode=1700000000123&returnUrl=https://merchant.test/return";

        assertTrue(service.verify(data, EXPECTED));
        assertTrue(service.verify(data, EXPECTED.toUpperCase()));
        assertFalse(service.verify(data + "x", EXPECTED));
        assertFalse(service.verify(data, null));
    }

    @Test
    void shouldRefuseToSignWithoutChecksumKey() {
        assertThrows(IllegalStateException.class, () -> service("").sign("data"));
    }

    private PayosSignatureService service(String checksumKey) {
        PayosProperties properties = new PayosProperties();
        properties.setChecksumKey(checksumKey);
        return new PayosSignatureService(properties);
    }
}
