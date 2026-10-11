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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Cổng vào HTTP của merchant để tạo thanh toán.
 *
 * Luồng: Client --POST /api/v1/payments--> PaymentController -> PaymentService.createPayment
 *        -> (DB: payments, transactions, idempotency_keys) -> trả về checkoutUrl để redirect khách sang VNPay.
 * Controller chỉ làm việc "dịch": validate cú pháp bằng Bean Validation, đổi request thành command, trả HTTP 201.
 * Mọi quy tắc nghiệp vụ nằm ở PaymentService.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;

    /**
     * Tạo thanh toán mới.
     *   - Header Idempotency-Key (bắt buộc): cùng key + cùng body => nhận lại kết quả cũ, không tạo thêm.
     *   - Body: orderId, amount (VND), currency, paymentMethod.
     *   - request.getRemoteAddr(): IP của bên gọi, được chuyển thành vnp_IpAddr gửi VNPay.
     * Trả 201 kèm paymentId và checkoutUrl; lỗi nghiệp vụ là ApiException (400/409) do GlobalExceptionHandler xử lý.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest servletRequest) {
        PaymentResponse response = createPaymentUseCase.createPayment(
                request.toCommand(idempotencyKey, servletRequest.getRemoteAddr()));
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}
