package com.thanh.payment.domain.exception;

/**
 * Order này đã có một payment đang hoạt động (PENDING hoặc REVIEW_REQUIRED), vi phạm unique "một payment active mỗi order".
 */
public class ActivePaymentExistsException extends RuntimeException {

    public ActivePaymentExistsException(Throwable cause) {
        super("Order already has an active payment", cause);
    }
}
