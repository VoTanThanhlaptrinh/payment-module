package com.thanh.payment.domain.model;

/**
 * Trạng thái của một Payment (Aggregate Root).
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
