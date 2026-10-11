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

    // Chỉ trả về provider đã bật cấu hình (enabled=true); chưa bật thì ném IllegalArgumentException, PaymentService đổi thành 400 PAYMENT_METHOD_NOT_ENABLED.
    public PaymentProvider getEnabledProvider(PaymentMethodType type) {
        PaymentProvider provider = getProvider(type);
        if (!provider.isEnabled()) {
            throw new IllegalArgumentException("Payment method is not enabled: " + type);
        }
        return provider;
    }
}
