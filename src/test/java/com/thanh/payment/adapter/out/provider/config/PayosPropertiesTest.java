package com.thanh.payment.adapter.out.provider.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayosPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void disabledProviderMayHaveEmptyCredentials() {
        assertTrue(validator.validate(new PayosProperties()).isEmpty());
    }

    @Test
    void enabledProviderWithCompleteConfigurationIsValid() {
        assertTrue(validator.validate(valid()).isEmpty());
    }

    @Test
    void enabledProviderRequiresEveryCredential() {
        PayosProperties noClient = valid();
        noClient.setClientId("");
        PayosProperties noKey = valid();
        noKey.setApiKey(null);
        PayosProperties noChecksum = valid();
        noChecksum.setChecksumKey(" ");

        assertFalse(validator.validate(noClient).isEmpty());
        assertFalse(validator.validate(noKey).isEmpty());
        assertFalse(validator.validate(noChecksum).isEmpty());
    }

    @Test
    void enabledProviderRequiresHttpsApiUrlAndValidCallbackUrls() {
        PayosProperties httpApi = valid();
        httpApi.setApiUrl("http://api-merchant.payos.vn");
        PayosProperties noReturn = valid();
        noReturn.setReturnUrl("");
        PayosProperties badCancel = valid();
        badCancel.setCancelUrl("not-a-url");

        assertFalse(validator.validate(httpApi).isEmpty());
        assertFalse(validator.validate(noReturn).isEmpty());
        assertFalse(validator.validate(badCancel).isEmpty());
    }

    @Test
    void enabledProviderRejectsOutOfRangeLimits() {
        PayosProperties longDescription = valid();
        longDescription.setDescriptionMaxLength(26);
        PayosProperties badPrefix = valid();
        badPrefix.setDescriptionPrefix("Đơn hàng");
        PayosProperties zeroTimeout = valid();
        zeroTimeout.setReadTimeout(Duration.ZERO);
        PayosProperties zeroExpiry = valid();
        zeroExpiry.setExpiryMinutes(0);

        assertFalse(validator.validate(longDescription).isEmpty());
        assertFalse(validator.validate(badPrefix).isEmpty());
        assertFalse(validator.validate(zeroTimeout).isEmpty());
        assertFalse(validator.validate(zeroExpiry).isEmpty());
    }

    private PayosProperties valid() {
        PayosProperties properties = new PayosProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setApiKey("api-key");
        properties.setChecksumKey("checksum-key");
        properties.setReturnUrl("https://merchant.test/return");
        properties.setCancelUrl("http://localhost:8080/cancel");
        return properties;
    }
}
