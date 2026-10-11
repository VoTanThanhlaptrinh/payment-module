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
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .totalAmount(payment.getTotalAmount() != null ? payment.getTotalAmount().getAmount() : null)
                .currency(payment.getTotalAmount() != null ? payment.getTotalAmount().getCurrency() : null)
                .method(payment.getMethod())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }

    // Dùng khi vừa tạo mới: lấy checkoutUrl, hạn và id giao dịch từ kết quả provider.
    public static PaymentResponse toResponseDTO(Payment payment, com.thanh.payment.application.port.out.ProviderCheckoutResult result) {
        if (payment == null) {
            return null;
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .totalAmount(payment.getTotalAmount() != null ? payment.getTotalAmount().getAmount() : null)
                .currency(payment.getTotalAmount() != null ? payment.getTotalAmount().getCurrency() : null)
                .method(payment.getMethod())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .transactionId(result != null ? result.getTransactionId() : null)
                .checkoutUrl(result != null ? result.getCheckoutUrl() : null)
                .expiresAt(result != null ? result.getExpiresAt() : null)
                .build();
    }

    // Dùng khi replay idempotency: dựng lại response y như lần đầu từ Transaction đã lưu (checkoutUrl, expiredAt).
    public static PaymentResponse toResponseDTO(Payment payment, com.thanh.payment.domain.model.Transaction transaction) {
        if (payment == null || transaction == null) {
            return null;
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .totalAmount(payment.getTotalAmount().getAmount())
                .currency(payment.getTotalAmount().getCurrency())
                .method(payment.getMethod())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .transactionId(String.valueOf(transaction.getId()))
                .checkoutUrl(transaction.getCheckoutUrl())
                .expiresAt(transaction.getExpiredAt())
                .build();
    }
}
