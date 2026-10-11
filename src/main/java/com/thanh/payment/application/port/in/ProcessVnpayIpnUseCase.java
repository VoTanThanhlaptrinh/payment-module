package com.thanh.payment.application.port.in;

import com.thanh.payment.application.dto.webhook.VnpayIpnResponse;

import java.util.List;
import java.util.Map;

public interface ProcessVnpayIpnUseCase {
    // Use case: xử lý IPN của VNPay. Nhận nguyên tham số query chưa lọc, luôn trả về RspCode theo quy ước VNPay. Cài đặt: VnpayIpnService.
    VnpayIpnResponse process(Map<String, List<String>> rawParameters);
}
