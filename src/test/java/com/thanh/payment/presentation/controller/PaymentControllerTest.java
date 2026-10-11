package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.port.in.CreatePaymentUseCase;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.PaymentStatus;
import com.thanh.payment.domain.valueobject.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CreatePaymentUseCase createPaymentUseCase;

    @Test
    void shouldRequireIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"amount\":5000,\"currency\":\"VND\",\"paymentMethod\":\"VNPAY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void shouldReturnBackwardCompatiblePaymentIdentifiers() throws Exception {
        when(createPaymentUseCase.createPayment(any())).thenReturn(PaymentResponse.builder()
                .id(10L).paymentId(10L).orderId(1L).totalAmount(java.math.BigDecimal.valueOf(5000))
                .currency(Currency.VND).method(PaymentMethodType.VNPAY).status(PaymentStatus.PENDING)
                .transactionId("20").checkoutUrl("https://sandbox.vnpayment.vn/signed").build());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"amount\":5000,\"currency\":\"VND\",\"paymentMethod\":\"VNPAY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.transactionId").value("20"));
    }
}
