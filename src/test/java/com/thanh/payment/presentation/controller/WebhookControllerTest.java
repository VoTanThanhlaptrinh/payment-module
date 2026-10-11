package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.application.port.in.ProcessVnpayIpnUseCase;
import com.thanh.payment.application.port.in.VerifyWebhookUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProcessVnpayIpnUseCase processVnpayIpnUseCase;
    @MockitoBean VerifyWebhookUseCase verifyWebhookUseCase;

    @Test
    void shouldReturnVnpayAcknowledgementAsJsonWithHttp200() throws Exception {
        when(processVnpayIpnUseCase.process(any())).thenReturn(VnpayIpnResponse.invalidSignature());

        mockMvc.perform(get("/api/v1/payments/webhook/vnpay")
                        .queryParam("vnp_SecureHash", "bad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("97"))
                .andExpect(jsonPath("$.Message").value("Invalid signature"));
    }

    @Test
    void returnUrlMustNotProcessPaymentState() throws Exception {
        mockMvc.perform(get("/api/v1/payments/webhook/vnpay/return")
                        .queryParam("vnp_TxnRef", "12345")
                        .queryParam("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "VNPay return received. Payment confirmation is pending the server-to-server IPN."));

        verifyNoInteractions(processVnpayIpnUseCase);
    }
}
