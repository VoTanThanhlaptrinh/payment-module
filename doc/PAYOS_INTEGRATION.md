# Tích hợp payOS (đối chiếu với VNPay)

Trạng thái 11/10/2026: **mới có bước tạo link thanh toán** (`POST /api/v1/payments` với `paymentMethod=PAYOS`). Webhook nhận kết quả và đối soát **chưa làm**, nên payment PayOS sẽ nằm `PENDING` cho tới khi có bước đó (xem mục 6).

Tài liệu gốc: <https://payos.vn/docs/api/>. Quản lý kênh thanh toán và khoá: <https://my.payos.vn>.

## 1. Điền cấu hình

1. Sao chép `.env.example` thành `.env` (nếu chưa có) và thêm các dòng PayOS vào `.env` hiện tại. `.env` đã được `.gitignore`, không commit.
2. Lấy ba khoá ở `my.payos.vn` > kênh thanh toán của bạn:

| Biến môi trường | Ý nghĩa | Bắt buộc |
| --- | --- | --- |
| `PAYOS_ENABLED` | `true` để bật PayOS | có |
| `PAYOS_CLIENT_ID` | Client ID, gửi trong header `x-client-id` | có khi bật |
| `PAYOS_API_KEY` | API Key, gửi trong header `x-api-key` | có khi bật |
| `PAYOS_CHECKSUM_KEY` | Checksum Key, dùng ký request và verify webhook | có khi bật |
| `PAYOS_RETURN_URL` | Trình duyệt khách về đây khi thanh toán xong | có khi bật |
| `PAYOS_CANCEL_URL` | Trình duyệt khách về đây khi bấm huỷ | có khi bật |
| `PAYOS_PARTNER_CODE` | Mã đối tác (nếu tham gia chương trình đối tác) | không |
| `PAYOS_API_URL` | Mặc định `https://api-merchant.payos.vn` (phải là https) | không |
| `PAYOS_EXPIRY_MINUTES` | Thời hạn link, mặc định 15 | không |
| `PAYOS_MIN_AMOUNT` | Số tiền tối thiểu, mặc định 2000 (**hãy kiểm tra lại với kênh của bạn**) | không |
| `PAYOS_DESCRIPTION_PREFIX` / `PAYOS_DESCRIPTION_MAX_LENGTH` | Nội dung chuyển khoản = prefix + orderId, mặc định `DH` và 9 ký tự | không |

Return/Cancel URL của PayOS chỉ cần là URL hợp lệ (http cũng được); khác VNPay, bạn không cần đăng ký IPN URL để **tạo link**.
App không khởi động nếu `PAYOS_ENABLED=true` mà thiếu khoá hoặc URL sai.

3. Khởi động bằng `.\mvnw.cmd spring-boot:run` rồi tạo payment:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payos-test-0001" \
  -d '{"orderId":1001,"amount":10000,"currency":"VND","paymentMethod":"PAYOS"}'
```

Response có `checkoutUrl` (trang `pay.payos.vn`). Mở link để xem mã QR. Lưu ý: PayOS không có sandbox riêng, thanh toán thật bằng tài khoản ngân hàng; dùng số tiền nhỏ.

## 2. Đối chiếu luồng VNPay và PayOS

| Bước | VNPay | PayOS |
| --- | --- | --- |
| Tạo thanh toán | Tự dựng URL đã ký, **không gọi mạng** | `POST /v2/payment-requests`, **gọi mạng**, nhận `checkoutUrl`, `qrCode`, `paymentLinkId` |
| Xác thực request | Ký cả URL: HMAC-SHA512 bằng `hashSecret` | Header `x-client-id` + `x-api-key`; thân request có `signature` HMAC-SHA256 bằng `checksumKey` |
| Dữ liệu được ký | Mọi `vnp_*`, sắp xếp, **URL-encode** | `amount, cancelUrl, description, orderCode, returnUrl`, sắp xếp, **giá trị thô** |
| Mã tham chiếu giao dịch | `vnp_TxnRef` = `Transaction.id` (TSID) | `orderCode` số nguyên ≤ 2^53−1, **không dùng được TSID** nên lấy từ sequence `provider_order_code_seq`, lưu ở `transactions.provider_order_code` |
| Số tiền | VND ×100, tối đa 12 chữ số | VND nguyên, đơn vị đồng |
| Hạn thanh toán | `vnp_ExpireDate` (yyyyMMddHHmmss, GMT+7) | `expiredAt` Unix timestamp **giây**, Int32 |
| Nội dung | `vnp_OrderInfo` tự do | `description` tối đa 9 ký tự (25 nếu liên kết ngân hàng qua payOS) |
| Báo kết quả | IPN `GET` + `vnp_SecureHash`, trả `RspCode` | Webhook `POST` JSON `{code, desc, success, data, signature}`, trả HTTP 2xx |
| Đăng ký callback | Gửi IPN URL cho VNPay | `POST /confirm-webhook` hoặc khai báo trong my.payos.vn |
| Khách quay về | `vnp_ReturnUrl` (chỉ hiển thị) | `returnUrl` / `cancelUrl` (chỉ hiển thị) |
| Đối soát khi mất callback | QueryDR, mã `91` = không có giao dịch | `GET /v2/payment-requests/{orderCode hoặc paymentLinkId}`, trạng thái `PENDING/PAID/CANCELLED/EXPIRED` |
| Huỷ link | không có | `POST /v2/payment-requests/{id}/cancel` |
| Môi trường test | sandbox riêng | không có sandbox, thanh toán thật |

## 3. Cách đã thể hiện trong code

| Việc | Lớp |
| --- | --- |
| Cấu hình, validate khi khởi động | `PayosProperties` (`payment.providers.payos.*`) |
| Ký HMAC-SHA256 và so sánh hằng thời gian | `PayosSignatureService` |
| Gọi API, kiểm tra response (`code=="00"`, `orderCode` khớp, `checkoutUrl` https) | `PayosAdapter` |
| Cấp `orderCode` duy nhất, không bao giờ lặp | `SequenceProviderOrderCodeGenerator` + `V5__provider_order_code.sql` (sequence bắt đầu từ epoch-ms để không đụng mã cũ nếu DB bị dựng lại) |
| Lưu `orderCode` cho webhook tra cứu | `Transaction.assignProviderOrderCode`, gọi trong `PaymentCreator` |
| Thời hạn theo cổng | `PaymentProvider.getExpiryMinutes()` |

Lỗi từ PayOS được đổi thành lỗi sạch cho client (không lộ khoá hay nội dung gốc): sai khoá hoặc bị từ chối là `502 PROVIDER_ERROR`; không kết nối được là `503 PROVIDER_UNAVAILABLE` kèm `Retry-After: 5`. Khi đó transaction rollback và `Idempotency-Key` được nhả, client thử lại được.

## 4. Khác biệt cần chú ý khi vận hành

- **Gọi mạng trong DB transaction.** `PaymentCreator` gọi PayOS khi đang giữ transaction (timeout 10 giây; PayOS mặc định connect 2s + read 5s). Trong lúc chờ, hàng `payments` của order bị khoá bởi unique index. Chấp nhận được ở quy mô nhỏ; hướng cải thiện: ghi `PENDING` rồi commit, gọi PayOS, rồi cập nhật.
- **Link mồ côi.** Nếu PayOS tạo link xong mà DB rollback, link vẫn tồn tại tới khi hết hạn nhưng không gắn payment nào. Webhook sau này phải bỏ qua `orderCode` không tìm thấy (trả 2xx).
- **`orderCode` là vĩnh viễn.** PayOS không nhận lại mã đã dùng (lỗi "Đơn thanh toán đã tồn tại"). Đừng reset `provider_order_code_seq`.
- **Không có IPN URL bịa.** PayOS chỉ cần `returnUrl`/`cancelUrl` để hiển thị, nhưng muốn nhận kết quả bạn phải có URL webhook công khai (https) và đăng ký ở bước sau.

## 5. Tạm thời xác nhận một thanh toán PayOS bằng tay

Chưa có webhook, nên sau khi thanh toán xong bạn xác nhận bằng cách gọi API tra cứu của PayOS rồi so với DB:

```sql
SELECT p.id AS payment_id, p.status, t.id AS transaction_id, t.provider_order_code, t.status AS transaction_status
FROM payments p JOIN transactions t ON t.payment_id = p.id
WHERE p.order_id = 1001;
```

Dùng `provider_order_code` để hỏi `GET /v2/payment-requests/{orderCode}` (header `x-client-id`, `x-api-key`) trên trang my.payos.vn hoặc công cụ HTTP. Đừng sửa trạng thái trong DB bằng tay nếu chưa thấy `PAID`.

## 6. Việc còn lại để dùng được end-to-end

1. **Webhook** `POST /api/v1/payments/webhook/payos`: verify chữ ký (HMAC-SHA256 trên `data` đã sắp xếp theo key) **trước khi** chạm DB, tìm giao dịch bằng `provider_order_code`, kiểm tra số tiền, khoá hàng, chuyển `COMPLETED` giống `VnpayIpnProcessor`. Trả 2xx cho cả `orderCode` lạ và bản gửi thử của `/confirm-webhook`.
2. **Đối soát** bằng `GET /v2/payment-requests/{orderCode}` cho giao dịch `PENDING` quá hạn, dùng lại khung `VnpayReconciliationScheduler`.
3. **Return/Cancel URL** thật của site merchant, và API `GET /api/v1/payments/{id}` để merchant biết kết quả (phụ thuộc xác thực, SEC-03).
4. Đăng ký webhook URL với PayOS (`POST /confirm-webhook`).

Chi tiết các phần dùng chung (API trạng thái, hàng đợi xử lý tay, metric) nằm trong [`plan/PROCESS_PAYMENT_READINESS.md`](plan/PROCESS_PAYMENT_READINESS.md).
