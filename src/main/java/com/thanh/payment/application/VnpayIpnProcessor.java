package com.thanh.payment.application;

import com.thanh.payment.api.dto.webhook.VnpayIpnData;
import com.thanh.payment.api.dto.webhook.VnpayIpnResponse;
import com.thanh.payment.domain.model.Payment;
import com.thanh.payment.domain.model.PaymentMethodType;
import com.thanh.payment.domain.model.Transaction;
import com.thanh.payment.domain.model.TransactionStatus;
import com.thanh.payment.domain.repository.PaymentRepository;
import com.thanh.payment.domain.repository.TransactionRepository;
import com.thanh.payment.domain.valueobject.Currency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Xử lý nghiệp vụ của một IPN ĐÃ qua verify chữ ký và TmnCode. Chạy trong một DB transaction duy nhất:
 * khoá hàng giao dịch -> kiểm tra dữ liệu -> chuyển trạng thái Payment + Transaction -> lưu.
 *
 * Thứ tự kiểm tra (dừng ở bước đầu tiên không đạt, không đổi trạng thái):
 *   1. vnp_TxnRef / vnp_Amount phải là số                         => 99
 *   2. Tìm thấy Transaction và Payment                            => 01
 *   3. Payment là VNPAY và Transaction là VND                      => 99
 *   4. vnp_Amount == số tiền đã lưu x 100                          => 04
 *   5. Transaction chưa kết thúc và Payment còn PENDING            => 02 (IPN lặp hoặc đã xử lý)
 *   6. Map mã VNPay sang trạng thái mới                           => 00 nếu ghi nhận được
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VnpayIpnProcessor {

    // Các mã ResponseCode mà VNPay dùng để báo ngân hàng từ chối/khách huỷ => coi là FAILED. Mã không nằm trong danh sách này (và không phải 00/07/11) bị xem là lạ => 99, không đổi trạng thái.
    private static final Set<String> DECLINED_CODES = Set.of(
            "09", "10", "12", "13", "24", "51", "65", "75", "79", "99");

    private final TransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    // Một transaction DB cho cả quá trình: khoá hàng giữ tới khi COMMIT, nên hai IPN trùng/đua nhau sẽ xếp hàng ở findByIdForUpdate
    // và IPN đến sau luôn thấy kết quả của IPN trước (=> 02).
    @Transactional
    public VnpayIpnResponse processVerified(VnpayIpnData data) {
        // Bước 1: vnp_TxnRef chính là Transaction.id; vnp_Amount là số tiền x 100. Không phải số => 99.
        Long transactionId;
        BigInteger callbackAmount;
        try {
            transactionId = Long.valueOf(data.get("vnp_TxnRef"));
            callbackAmount = new BigInteger(data.get("vnp_Amount"));
        } catch (NumberFormatException exception) {
            return VnpayIpnResponse.invalidRequest();
        }

        // Bước 2: SELECT ... FOR UPDATE khoá hàng giao dịch này cho tới hết transaction.
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (transaction == null) {
            return VnpayIpnResponse.notFound();
        }
        // Payment cha của giao dịch (cần kiểm tra method và trạng thái).
        Payment payment = paymentRepository.findById(transaction.getPaymentId()).orElse(null);
        if (payment == null) {
            return VnpayIpnResponse.notFound();
        }
        // Bước 3: chỉ xử lý giao dịch VNPay bằng VND.
        if (payment.getMethod() != PaymentMethodType.VNPAY
                || transaction.getAmount().getCurrency() != Currency.VND) {
            return VnpayIpnResponse.invalidRequest();
        }
        // Bước 4: so với số tiền ĐÃ LƯU trong DB (không tin con số nào ngoài DB): VND x 100, tính bằng BigInteger để không sai số.
        BigInteger expectedAmount;
        try {
            expectedAmount = transaction.getAmount().getAmount().toBigIntegerExact()
                    .multiply(BigInteger.valueOf(100));
        } catch (ArithmeticException exception) {
            return VnpayIpnResponse.invalidRequest();
        }
        if (!expectedAmount.equals(callbackAmount)) {
            return VnpayIpnResponse.amountMismatch();
        }
        // Bước 5: đã kết thúc rồi (IPN lặp, hoặc đã được QueryDR/IPN trước đó xử lý) => 02, tuyệt đối không ghi đè.
        if (transaction.isTerminal() || !payment.isPending()) {
            // VNPay báo THÀNH CÔNG cho giao dịch mà ta đã đóng là EXPIRED/FAILED (vd IPN đến muộn): khách có thể đã bị trừ tiền.
            // Vẫn trả 02 (giữ hợp đồng với VNPay, không tự đổi trạng thái) nhưng log ERROR để vận hành hoàn tiền/xử lý tay.
            if ("00".equals(data.get("vnp_ResponseCode")) && "00".equals(data.get("vnp_TransactionStatus"))
                    && (transaction.getStatus() == TransactionStatus.EXPIRED
                    || transaction.getStatus() == TransactionStatus.FAILED)) {
                log.error("VNPay late success on closed transaction txnRef={} localStatus={}",
                        transaction.getId(), transaction.getStatus());
            }
            return VnpayIpnResponse.alreadyConfirmed();
        }

        // Bước 6: đổi mã VNPay thành trạng thái. Quy tắc theo thứ tự:
        //   - ResponseCode 00 VÀ TransactionStatus 00                    => thành công (COMPLETED/SUCCESS)
        //   - ResponseCode 07 (+ status 00 hoặc 07)                      => nghi ngờ (REVIEW_REQUIRED), chờ người duyệt
        //   - chỉ MỘT trong hai là 00 (mâu thuẫn)                        => 99, không đổi gì
        //   - ResponseCode 11                                            => hết hạn (EXPIRED)
        //   - ResponseCode thuộc DECLINED_CODES và status khác 00        => thất bại (FAILED)
        //   - còn lại (mã lạ)                                            => 99, không đổi gì (chờ IPN/QueryDR sau)
        String responseCode = data.get("vnp_ResponseCode");
        String transactionStatus = data.get("vnp_TransactionStatus");
        String providerTransactionId = data.get("vnp_TransactionNo");
        // Lấy giờ từ Clock của ứng dụng (không phải đồng hồ ngầm trong domain) để completed_at nhất quán và test được.
        OffsetDateTime now = OffsetDateTime.now(clock);

        if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
            // Thành công: cập nhật cả Payment và Transaction (lưu vnp_TransactionNo để đối chiếu với VNPay sau này).
            payment.markCompleted();
            transaction.markSuccess(providerTransactionId, responseCode, now);
            // Chi tiết để đối chiếu sao kê: giờ trả tiền (vnp_PayDate), ngân hàng, loại thẻ, mã giao dịch ngân hàng. Đều tuỳ chọn.
            transaction.recordPaymentDetails(data.get("vnp_PayDate"), data.get("vnp_BankCode"),
                    data.get("vnp_CardType"), data.get("vnp_BankTranNo"));
        } else if ("07".equals(responseCode)
                && ("00".equals(transactionStatus) || "07".equals(transactionStatus))) {
            // Nghi ngờ gian lận: không giao hàng tự động, cũng không cho tạo payment mới cho order này.
            payment.markReviewRequired();
            transaction.markReviewRequired(providerTransactionId, responseCode,
                    "VNPay marked the transaction as suspicious", now);
        } else if ("00".equals(responseCode) || "00".equals(transactionStatus)) {
            return VnpayIpnResponse.invalidRequest();
        } else if ("11".equals(responseCode)) {
            // Hết thời gian chờ thanh toán.
            payment.markExpired();
            transaction.markExpired(providerTransactionId, responseCode, "VNPay payment timeout", now);
        } else if (DECLINED_CODES.contains(responseCode) && !"00".equals(transactionStatus)) {
            // Bị từ chối/khách huỷ: order sau đó được phép tạo lại payment mới.
            payment.markFailed();
            transaction.markFailed(providerTransactionId, responseCode,
                    "VNPay declined transaction with response code " + responseCode, now);
        } else {
            return VnpayIpnResponse.invalidRequest();
        }

        // Lưu cả hai trong cùng transaction: hoặc cùng được ghi, hoặc cùng rollback. Version (khoá lạc quan) tăng lên.
        paymentRepository.save(payment);
        transactionRepository.save(transaction);
        // 00: đã ghi nhận. COMMIT xảy ra khi method kết thúc và nhả khoá hàng.
        return VnpayIpnResponse.processed();
    }
}
