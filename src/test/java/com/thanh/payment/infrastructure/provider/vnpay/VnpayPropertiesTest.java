package com.thanh.payment.infrastructure.provider.vnpay;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VnpayPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void disabledProviderMayHaveEmptyCredentials() {
        VnpayProperties properties = new VnpayProperties();
        properties.setEnabled(false);

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void enabledProviderMustHaveCompleteValidConfiguration() {
        VnpayProperties properties = new VnpayProperties();
        properties.setEnabled(true);
        properties.setPaymentUrl("not-a-url");
        properties.setReturnUrl("");
        properties.setTmnCode("");
        properties.setHashSecret("");

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void enabledProviderAcceptsCompleteReconciliationConfiguration() {
        VnpayProperties properties = validProperties();
        properties.setReconciliationEnabled(true);
        properties.setQueryApiUrl("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        properties.setMerchantIp("127.0.0.1");
        properties.setReconciliationInterval(Duration.ofMinutes(5));

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void enabledProviderRequiresHttpsIpnUrl() {
        VnpayProperties properties = validProperties();
        properties.setIpnUrl("http://localhost:8080/api/v1/payments/webhook/vnpay");

        assertFalse(validator.validate(properties).isEmpty());
    }

    private VnpayProperties validProperties() {
        VnpayProperties properties = new VnpayProperties();
        properties.setEnabled(true);
        properties.setPaymentUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setReturnUrl("https://merchant.test/payments/vnpay/return");
        properties.setIpnUrl("https://merchant.test/api/v1/payments/webhook/vnpay");
        properties.setTmnCode("TEST0001");
        properties.setHashSecret("test-secret");
        return properties;
    }
}
