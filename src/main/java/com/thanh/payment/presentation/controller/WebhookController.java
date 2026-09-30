package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.dto.webhook.BankWebhookRequest;
import com.thanh.payment.application.dto.webhook.MomoIpnRequest;
import com.thanh.payment.application.dto.webhook.VnpayIpnRequest;
import com.thanh.payment.application.port.in.VerifyWebhookUseCase;
import com.thanh.payment.domain.model.PaymentMethodType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final VerifyWebhookUseCase verifyWebhookUseCase;

    @GetMapping("/vnpay")
    public ResponseEntity<String> vnpayIpn(@ModelAttribute VnpayIpnRequest request) {
        verifyWebhookUseCase.verifyWebhook(PaymentMethodType.VNPAY, request);
        return ResponseEntity.ok("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}");
    }

    @PostMapping("/momo")
    public ResponseEntity<Void> momoIpn(@RequestBody MomoIpnRequest request) {
        verifyWebhookUseCase.verifyWebhook(PaymentMethodType.MOMO, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bank")
    public ResponseEntity<String> bankWebhook(@RequestBody BankWebhookRequest request) {
        verifyWebhookUseCase.verifyWebhook(PaymentMethodType.BANK_TRANSFER, request);
        return ResponseEntity.ok("success");
    }
}
