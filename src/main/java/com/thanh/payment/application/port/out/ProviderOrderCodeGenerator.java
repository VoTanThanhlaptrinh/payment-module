package com.thanh.payment.application.port.out;

// Cấp mã đơn dạng số, duy nhất toàn hệ thống, cho cổng yêu cầu số nguyên nhỏ (payOS orderCode <= 2^53-1). Mã không bao giờ được cấp lại.
public interface ProviderOrderCodeGenerator {
    long next();
}
