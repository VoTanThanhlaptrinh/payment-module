package com.thanh.payment.api;

import com.thanh.payment.api.dto.webhook.BankWebhookRequest;
import com.thanh.payment.api.dto.webhook.MomoIpnRequest;
import com.thanh.payment.api.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.application.PaymentService;
import com.thanh.payment.application.VnpayIpnService;
import com.thanh.payment.domain.enums.PaymentMethodType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;

// Nhận callback từ các cổng thanh toán. Với VNPay có hai đường: IPN (server-to-server, đáng tin) và Return URL (qua trình duyệt khách, chỉ để hiển thị).
@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;
    private final VnpayIpnService vnpayIpnService;

    /**
     * IPN của VNPay: VNPay gọi GET kèm các tham số vnp_* và vnp_SecureHash.
     * Nhận toàn bộ tham số thô (MultiValueMap) để kiểm tra chữ ký trên đúng dữ liệu VNPay gửi (kể cả tham số trùng/lạ).
     * LUÔN trả HTTP 200; kết quả nghiệp vụ nằm trong RspCode của body (00/01/02/04/97/99) để VNPay biết có cần gửi lại không.
     */
    @GetMapping("/vnpay")
    public ResponseEntity<VnpayIpnResponse> vnpayIpn(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok(vnpayIpnService.process(parameters));
    }

    // Return URL: nơi trình duyệt khách được VNPay đưa về. KHÔNG đọc tham số, KHÔNG đổi trạng thái payment: dữ liệu đi qua trình duyệt nên không đáng tin. Trạng thái chỉ do IPN/QueryDR quyết định.
    @GetMapping("/vnpay/return")
    public ResponseEntity<String> vnpayReturn(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok(
                "VNPay return received. Payment confirmation is pending the server-to-server IPN.");
    }

    // MoMo/ngân hàng: đường cũ (verifyWebhook -> processTransaction), chưa kiểm tra số tiền và hiện không với tới được vì provider đang bị tắt. Cần viết processor riêng trước khi bật.
    @PostMapping("/momo")
    public ResponseEntity<Void> momoIpn(@RequestBody MomoIpnRequest request) {
        paymentService.verifyWebhook(PaymentMethodType.MOMO, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bank")
    public ResponseEntity<String> bankWebhook(@RequestBody BankWebhookRequest request) {
        paymentService.verifyWebhook(PaymentMethodType.BANK_TRANSFER, request);
        return ResponseEntity.ok("success");
    }
}
