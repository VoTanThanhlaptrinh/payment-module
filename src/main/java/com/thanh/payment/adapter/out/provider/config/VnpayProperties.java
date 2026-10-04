package com.thanh.payment.adapter.out.provider.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "payment.providers.vnpay")
public class VnpayProperties {
    private boolean enabled;
    
    @NotBlank
    private String paymentUrl;
    
    @NotBlank
    private String returnUrl;
    
    @NotBlank
    private String tmnCode;
    
    @NotBlank
    private String hashSecret;
    
    @NotBlank
    private String version = "2.1.0";
    
    @NotNull
    private Integer expiryMinutes = 15;
}
