package com.thanh.payment.application;

import com.thanh.payment.infrastructure.provider.vnpay.VnpayIpnVerifier;
import com.thanh.payment.infrastructure.provider.vnpay.VnpaySignatureService;
import com.thanh.payment.infrastructure.provider.vnpay.VnpayProperties;
import com.thanh.payment.api.dto.webhook.VnpayIpnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VnpayIpnServiceTest {

    @Mock VnpayIpnProcessor processor;
    private VnpayProperties properties;
    private VnpaySignatureService signatures;
    private VnpayIpnService service;

    @BeforeEach
    void setUp() {
        properties = new VnpayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("TEST0001");
        properties.setHashSecret("test-secret");
        signatures = new VnpaySignatureService(properties);
        service = new VnpayIpnService(new VnpayIpnVerifier(signatures), properties, processor);
    }

    @Test
    void invalidSignatureMustNotReachDatabaseProcessor() {
        Map<String, List<String>> raw = rawParameters("00");
        raw.put("vnp_SecureHash", List.of("0".repeat(128)));

        VnpayIpnResponse response = service.process(raw);

        assertEquals("97", response.rspCode());
        verifyNoInteractions(processor);
    }

    @Test
    void missingSignatureMustNotReachDatabaseProcessor() {
        VnpayIpnResponse response = service.process(rawParameters("00"));

        assertEquals("97", response.rspCode());
        verifyNoInteractions(processor);
    }

    @Test
    void wrongTmnCodeMustNotReachDatabaseProcessor() {
        Map<String, List<String>> raw = rawParameters("00");
        Map<String, String> signable = flatten(raw);
        signable.put("vnp_TmnCode", "WRONG001");
        raw.put("vnp_TmnCode", List.of("WRONG001"));
        raw.put("vnp_SecureHash", List.of(signatures.sign(signable)));

        VnpayIpnResponse response = service.process(raw);

        assertEquals("99", response.rspCode());
        verifyNoInteractions(processor);
    }

    @Test
    void validSignatureShouldReachTransactionalProcessor() {
        Map<String, List<String>> raw = rawParameters("00");
        raw.put("vnp_SecureHash", List.of(signatures.sign(flatten(raw))));
        when(processor.processVerified(any())).thenReturn(VnpayIpnResponse.processed());

        assertEquals("00", service.process(raw).rspCode());
        verify(processor).processVerified(any());
    }

    private Map<String, List<String>> rawParameters(String responseCode) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("vnp_TmnCode", List.of("TEST0001"));
        raw.put("vnp_Amount", List.of("500000"));
        raw.put("vnp_TxnRef", List.of("12345"));
        raw.put("vnp_TransactionNo", List.of("98765"));
        raw.put("vnp_ResponseCode", List.of(responseCode));
        raw.put("vnp_TransactionStatus", List.of(responseCode));
        return raw;
    }

    private Map<String, String> flatten(Map<String, List<String>> raw) {
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!"vnp_SecureHash".equals(key)) {
                values.put(key, value.get(0));
            }
        });
        return values;
    }
}
