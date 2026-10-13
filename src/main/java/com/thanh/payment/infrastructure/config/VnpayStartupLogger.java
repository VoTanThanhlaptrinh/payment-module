package com.thanh.payment.infrastructure.config;

import com.thanh.payment.infrastructure.provider.vnpay.VnpayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VnpayStartupLogger {

    private final VnpayProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void reportCallbackConfiguration() {
        if (properties.isEnabled()) {
            log.info("VNPay enabled ipnUrl={} reconciliationEnabled={}",
                    properties.getIpnUrl(), properties.isReconciliationEnabled());
        }
    }
}
