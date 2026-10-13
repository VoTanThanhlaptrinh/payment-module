package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.application.exception.ApiException;
import com.thanh.payment.application.service.PaymentService;
import com.thanh.payment.application.service.VnpayIpnService;
import com.thanh.payment.domain.model.PaymentMethodType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    private static final String IPN_PATH = "/api/v1/payments/webhook/vnpay";

    @Autowired MockMvc mockMvc;
    @MockitoBean VnpayIpnService vnpayIpnService;
    @MockitoBean PaymentService paymentService;

    @Test
    void shouldReturnVnpayAcknowledgementAsJsonWithHttp200() throws Exception {
        when(vnpayIpnService.process(any())).thenReturn(VnpayIpnResponse.invalidSignature());

        mockMvc.perform(get(IPN_PATH)
                        .queryParam("vnp_SecureHash", "bad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("97"))
                .andExpect(jsonPath("$.Message").value("Invalid signature"));
    }

    // Contract với VNPay: mọi RspCode đều phải là HTTP 200 + JSON {RspCode, Message}; VNPay chỉ nhìn RspCode để quyết định gửi lại IPN.
    @Test
    void everyIpnOutcomeMustBeHttp200WithRspCodeAndMessage() throws Exception {
        assertIpn(VnpayIpnResponse.processed(), "00");
        assertIpn(VnpayIpnResponse.notFound(), "01");
        assertIpn(VnpayIpnResponse.alreadyConfirmed(), "02");
        assertIpn(VnpayIpnResponse.amountMismatch(), "04");
        assertIpn(VnpayIpnResponse.invalidSignature(), "97");
        assertIpn(VnpayIpnResponse.invalidRequest(), "99");
    }

    @Test
    void returnUrlMustNotProcessPaymentState() throws Exception {
        mockMvc.perform(get("/api/v1/payments/webhook/vnpay/return")
                        .queryParam("vnp_TxnRef", "12345")
                        .queryParam("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "VNPay return received. Payment confirmation is pending the server-to-server IPN."));

        verifyNoInteractions(vnpayIpnService);
    }

    @Test
    void disabledProviderWebhookMustAnswerNotFoundEnvelope() throws Exception {
        when(paymentService.verifyWebhook(eq(PaymentMethodType.MOMO), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "PROVIDER_DISABLED", "disabled"));

        mockMvc.perform(post("/api/v1/payments/webhook/momo")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_DISABLED"));
    }

    @Test
    void invalidWebhookSignatureMustAnswerUnauthorizedEnvelope() throws Exception {
        when(paymentService.verifyWebhook(eq(PaymentMethodType.BANK_TRANSFER), any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "bad signature"));

        mockMvc.perform(post("/api/v1/payments/webhook/bank")
                        .contentType("application/json")
                        .content("{\"success\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void malformedWebhookBodyMustAnswerBadRequestNotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhook/momo")
                        .contentType("application/json")
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BODY"));
    }

    private void assertIpn(VnpayIpnResponse response, String rspCode) throws Exception {
        when(vnpayIpnService.process(any())).thenReturn(response);
        mockMvc.perform(get(IPN_PATH).queryParam("vnp_TxnRef", "12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value(rspCode))
                .andExpect(jsonPath("$.Message").isNotEmpty());
    }
}
