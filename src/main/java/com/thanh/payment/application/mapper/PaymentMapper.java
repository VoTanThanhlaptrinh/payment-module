package com.thanh.payment.application.mapper;

import com.thanh.payment.application.dto.PaymentResponse;
import com.thanh.payment.domain.model.Payment;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentResponse toResponseDTO(Payment payment) {
        if (payment == null) {
            return null;
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .totalAmount(payment.getTotalAmount() != null ? payment.getTotalAmount().getAmount() : null)
                .currency(payment.getTotalAmount() != null ? payment.getTotalAmount().getCurrency() : null)
                .method(payment.getMethod())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
