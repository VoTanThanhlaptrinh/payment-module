package com.thanh.payment.adapter.out.provider;

import com.thanh.payment.adapter.out.provider.config.VnpayProperties;
import com.thanh.payment.application.dto.webhook.VnpayIpnData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VnpayIpnVerifierTest {

    private VnpaySignatureService signatures;
    private VnpayIpnVerifier verifier;

    @BeforeEach
    void setUp() {
        VnpayProperties properties = new VnpayProperties();
        properties.setHashSecret("test-secret");
        signatures = new VnpaySignatureService(properties);
        verifier = new VnpayIpnVerifier(signatures);
    }

    @Test
    void shouldVerifySignedPayloadAndRejectTampering() {
        Map<String, String> fields = fields();
        Map<String, List<String>> raw = raw(fields, signatures.sign(fields));

        assertTrue(verifier.verify(raw).signatureValid());

        raw.put("vnp_Amount", List.of("999900"));
        assertFalse(verifier.verify(raw).signatureValid());
    }

    @Test
    void shouldRejectDuplicateQueryParameter() {
        Map<String, String> fields = fields();
        Map<String, List<String>> raw = raw(fields, signatures.sign(fields));
        raw.put("vnp_Amount", List.of("500000", "999900"));

        VnpayIpnData result = verifier.verify(raw);

        assertTrue(result.malformed());
        assertFalse(result.signatureValid());
    }

    @Test
    void shouldIgnoreUnsignedNonVnpQueryParameters() {
        Map<String, String> fields = fields();
        Map<String, List<String>> raw = raw(fields, signatures.sign(fields));
        raw.put("tracking", List.of("ignored"));

        assertTrue(verifier.verify(raw).signatureValid());
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vnp_TmnCode", "TEST0001");
        fields.put("vnp_Amount", "500000");
        fields.put("vnp_TxnRef", "12345");
        fields.put("vnp_TransactionNo", "98765");
        fields.put("vnp_ResponseCode", "00");
        fields.put("vnp_TransactionStatus", "00");
        return fields;
    }

    private Map<String, List<String>> raw(Map<String, String> fields, String signature) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        fields.forEach((key, value) -> raw.put(key, new ArrayList<>(List.of(value))));
        raw.put("vnp_SecureHash", List.of(signature));
        return raw;
    }
}
