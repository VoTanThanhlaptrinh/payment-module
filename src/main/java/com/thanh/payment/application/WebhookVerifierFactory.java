package com.thanh.payment.application;

import com.thanh.payment.application.provider.WebhookVerifier;
import com.thanh.payment.domain.enums.PaymentMethodType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WebhookVerifierFactory {
    private final Map<PaymentMethodType, WebhookVerifier> verifiers;

    public WebhookVerifierFactory(List<WebhookVerifier> verifiers) {
        this.verifiers = verifiers.stream()
                .collect(Collectors.toMap(WebhookVerifier::getSupportedType, Function.identity()));
    }

    public WebhookVerifier getVerifier(PaymentMethodType type) {
        WebhookVerifier verifier = verifiers.get(type);
        if (verifier == null) {
            throw new IllegalArgumentException("Unsupported webhook provider: " + type);
        }
        return verifier;
    }
}
