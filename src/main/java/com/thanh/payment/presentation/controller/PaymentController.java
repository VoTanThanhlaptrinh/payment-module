package com.thanh.payment.presentation.controller;

import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.application.port.in.CreatePaymentUseCase;
import com.thanh.payment.presentation.dto.CreatePaymentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse response = createPaymentUseCase.createPayment(request.toCommand());
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}
