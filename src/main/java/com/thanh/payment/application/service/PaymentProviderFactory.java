package com.thanh.payment.application.service;

import com.thanh.payment.application.port.out.PaymentProvider;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentProviderFactory {

    private final Map<PaymentMethodType, PaymentProvider> providers;

    public PaymentProviderFactory(List<PaymentProvider> paymentProviderList) {
        this.providers = paymentProviderList.stream()
                .collect(Collectors.toMap(PaymentProvider::getSupportedType, Function.identity()));
    }

    public PaymentProvider getProvider(PaymentMethodType type) {
        PaymentProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported payment method: " + type);
        }
        return provider;
    }
}
