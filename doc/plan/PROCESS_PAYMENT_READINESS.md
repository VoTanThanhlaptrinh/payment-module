# Đánh giá sẵn sàng: xử lý kết quả thanh toán (process payment)

Ngày đánh giá: 11/10/2026. Phạm vi: mọi bước sau khi `POST /api/v1/payments` trả `checkoutUrl`. Đó là VNPay báo kết quả qua IPN, khách quay về Return URL, đối soát QueryDR phục hồi IPN bị mất, và merchant hoặc vận hành biết được kết quả để xử lý tiếp.

## Kết luận

**Chưa dùng được end-to-end.** Phần lõi xử lý IPN đã đúng và an toàn. Tuy vậy, chỉ có database biết kết quả thanh toán: hệ thống đơn hàng của merchant, khách hàng và người vận hành đều không có đường nào để biết hoặc xử lý tiếp. Code cũng chưa từng chạy với VNPay sandbox thật.

Bốn điểm **chặn** (P1–P4) phải xong trước khi cho merchant dùng. Các điểm C1–C9 cần sửa để chạy ổn định ở production.

## Phạm vi đã soát

| Thành phần | Mã nguồn |
| --- | --- |
| IPN | [`WebhookController.vnpayIpn`](../../src/main/java/com/thanh/payment/api/WebhookController.java#L33) → [`VnpayIpnService`](../../src/main/java/com/thanh/payment/application/VnpayIpnService.java) → [`VnpayIpnVerifier`](../../src/main/java/com/thanh/payment/infrastructure/provider/vnpay/VnpayIpnVerifier.java) → [`VnpayIpnProcessor`](../../src/main/java/com/thanh/payment/application/VnpayIpnProcessor.java) |
| Return URL | [`WebhookController.vnpayReturn`](../../src/main/java/com/thanh/payment/api/WebhookController.java#L39) |
| Đối soát | [`VnpayReconciliationScheduler`](../../src/main/java/com/thanh/payment/infrastructure/job/VnpayReconciliationScheduler.java) → [`VnpayReconciliationService`](../../src/main/java/com/thanh/payment/application/VnpayReconciliationService.java) → [`VnpayReconciliationProcessor`](../../src/main/java/com/thanh/payment/application/VnpayReconciliationProcessor.java) |
| Domain / schema | [`Payment`](../../src/main/java/com/thanh/payment/domain/model/Payment.java), [`Transaction`](../../src/main/java/com/thanh/payment/domain/model/Transaction.java), [`V1__payment_schema.sql`](../../src/main/resources/db/migration/V1__payment_schema.sql), [`V2__vnpay_reconciliation.sql`](../../src/main/resources/db/migration/V2__vnpay_reconciliation.sql) |
| Đường cũ MoMo/bank | [`PaymentService.processTransaction` / `verifyWebhook`](../../src/main/java/com/thanh/payment/application/PaymentService.java#L177) |
| Test hiện có | `VnpayIpnProcessorTest` (10 case), `VnpayIpnServiceTest` (4), `WebhookControllerTest` (2), `VnpayIpnPersistenceIntegrationTest` (1), các test đối soát |

## 1. Những gì đã đạt

- Chữ ký HMAC-SHA512 được verify **trước** mọi truy vấn DB. Tham số xuất hiện nhiều lần bị từ chối. TmnCode được kiểm tra.
- Hàng giao dịch bị khoá bằng `SELECT … FOR UPDATE`, nên hai IPN trùng nhau phải xếp hàng. Số tiền được so với giá trị trong DB ×100 bằng `BigInteger`. Method và currency được kiểm tra.
- Mapping mã đủ trường hợp:
  - `00/00` → thành công;
  - `07` → cần duyệt;
  - hai mã mâu thuẫn → 99;
  - `11` → hết hạn;
  - mã từ chối → thất bại;
  - mã lạ → 99, không đổi trạng thái.
- IPN lặp lại trả `02` và không ghi đè trạng thái.
- Luôn trả HTTP 200 với `RspCode`/`Message` đúng contract VNPay. Lỗi bất ngờ trả 99 để VNPay gửi lại.
- QueryDR được gọi ngoài DB transaction, sau đó khoá hàng và kiểm tra lại trạng thái. `91` chỉ được coi là hết hạn sau khi qua grace. Số lần thử có giới hạn, và đối soát không bao giờ ghi đè giao dịch đã kết thúc.
- Return URL không đổi trạng thái.

## 2. Điểm cần cải thiện

| # | Mức | Vấn đề (bằng chứng) | Hệ quả |
| --- | --- | --- | --- |
| P1 | **Chặn** | Chưa chạy IPN/QueryDR thật với VNPay sandbox. Ý nghĩa mã `91` của QueryDR chưa được xác minh (roadmap M1). | Chưa có bằng chứng code khớp payload thật của VNPay. |
| P2 | **Chặn** | [`PaymentController`](../../src/main/java/com/thanh/payment/api/PaymentController.java#L39) chỉ có `POST`: không có `GET /api/v1/payments/{id}` hay `?orderId=`, cũng không có event hay callback cho merchant. | Hệ thống đơn hàng không biết payment đã `COMPLETED` nên không giao hàng được. |
| P3 | **Chặn** | [`vnpayReturn`](../../src/main/java/com/thanh/payment/api/WebhookController.java#L39) trả một chuỗi text cố định. | Khách thanh toán xong bị kẹt ở trang text, không quay lại site merchant. |
| P4 | **Chặn** | Không có cách xử lý tay cho ba trường hợp: `REVIEW_REQUIRED`, giao dịch đối soát exhausted, và IPN thành công đến muộn ([chỉ log ERROR](../../src/main/java/com/thanh/payment/application/VnpayIpnProcessor.java#L85)). | [`uq_payments_order_id_active`](../../src/main/resources/db/migration/V1__payment_schema.sql#L14) tính cả `REVIEW_REQUIRED`, còn giao dịch exhausted vẫn `PENDING`, nên order bị khoá vĩnh viễn. Khách có thể đã bị trừ tiền mà không có hàng đợi nào để xử lý. |
| C1 | Cao | [Vòng lặp scheduler](../../src/main/java/com/thanh/payment/infrastructure/job/VnpayReconciliationScheduler.java#L48) không bắt lỗi. `VnpayReconciliationService` chỉ bắt lỗi khi gọi mạng; lỗi trong `processor.apply` (DB lỗi, optimistic lock) ném thẳng ra ngoài. | Cả lô bị dừng. Một giao dịch lỗi đứng đầu lô chặn các giao dịch sau ở mọi chu kỳ. |
| C2 | Cao | Nhánh [`INVALID_LOCAL_STATE`](../../src/main/java/com/thanh/payment/application/VnpayReconciliationProcessor.java#L51) return trước `recordReconciliationAttempt`, nên `next_reconciliation_at` không được dời. | Hàng đó bị chọn lại mãi ở đầu `ORDER BY next_reconciliation_at`. Chỉ cần đủ `batchSize` hàng như vậy là đối soát đứng hẳn. |
| C3 | Cao | Chạy nhiều instance thì không có cơ chế giành quyền xử lý. Mỗi instance đọc cùng một danh sách ứng viên ([`findReconciliationCandidates`](../../src/main/java/com/thanh/payment/domain/repository/TransactionRepository.java#L34)). | QueryDR bị gọi trùng. `reconciliation_attempts` bị cộng nhiều lần nên giao dịch exhausted sớm hơn cấu hình. |
| C4 | Trung bình | Không lưu IPN thô (WEB-08). Chỉ có log. | Không có bằng chứng khi tranh chấp với khách hoặc VNPay, khó điều tra sự cố. |
| C5 | Trung bình | [`paid_at` chưa bao giờ được gán](../../src/main/java/com/thanh/payment/domain/model/Transaction.java#L103). Không lưu `vnp_PayDate`, `vnp_BankCode`, `vnp_CardType`, `vnp_BankTranNo`. | Thiếu dữ liệu để đối soát kế toán với sao kê VNPay. |
| C6 | Thấp | [`Transaction.mark*`](../../src/main/java/com/thanh/payment/domain/model/Transaction.java#L225) dùng `OffsetDateTime.now()` thay vì `Clock`. | `completed_at` không theo clock của service, khó viết test dùng thời gian cố định. |
| C7 | Thấp | Đường cũ [`verifyWebhook` → `processTransaction`](../../src/main/java/com/thanh/payment/application/PaymentService.java#L219) và route [`/webhook/momo`, `/webhook/bank`](../../src/main/java/com/thanh/payment/api/WebhookController.java#L46) vẫn mở. Đường này không kiểm số tiền, và `SecurityException`/`IllegalStateException` rơi vào `handleUnexpected` thành 500. | Bề mặt tấn công thừa, lỗi trả về sai contract. |
| C8 | Thấp | `WebhookControllerTest` chỉ có 2 case. Chưa có contract MVC cho từng `RspCode`, chưa có test cô lập lỗi trong lô của scheduler, và IPN chưa chạy trên schema Flyway trong CI. | WEB-09/10/11 chưa đủ bằng chứng để tick. |
| C9 | Thấp | Không có metric hay cảnh báo cho `RspCode`, kết quả đối soát, thành công muộn hoặc exhausted. | Sự cố về tiền chỉ lộ ra khi có người đọc log. |

## 3. Kế hoạch cải thiện

Làm theo thứ tự giai đoạn. Mỗi giai đoạn kết thúc bằng `./mvnw test` xanh và một commit có scope.

### Giai đoạn 1: sửa độ bền, làm trước khi chạy sandbox (C1, C2, C5, C6, C7, C8)

1. **Cô lập lỗi trong lô (C1).** Trong `VnpayReconciliationScheduler.reconcileDueTransactions`, bọc từng `reconcile(transaction)` bằng try/catch `RuntimeException`. Chỉ log `transactionId` và loại lỗi rồi chạy tiếp giao dịch sau.
   - Test: `VnpayReconciliationSchedulerTest`, giao dịch thứ nhất ném lỗi thì giao dịch thứ hai vẫn được xử lý.
2. **Không để hàng kẹt đầu lô (C2).** Trong `VnpayReconciliationProcessor.apply`, nhánh `INVALID_LOCAL_STATE` (khi transaction còn tồn tại) ghi `reconciliation_exhausted=true` và `last_reconciliation_code=INVALID_LOCAL_STATE`, rồi log ERROR. Có thể thêm method domain `Transaction.markReconciliationBlocked(code)`.
   - Test: `VnpayReconciliationProcessorTest`, payment không còn PENDING thì transaction bị đánh dấu exhausted và không bị chọn lại.
3. **Lưu dữ liệu thanh toán từ VNPay (C5).**
   - Migration mới `V4__transactions_provider_details.sql` thêm `provider_bank_code VARCHAR(20)`, `provider_card_type VARCHAR(20)`, `provider_bank_tran_no VARCHAR(255)`.
   - `markSuccess` nhận thêm `paidAt` (parse `vnp_PayDate` dạng `yyyyMMddHHmmss`, múi `Asia/Ho_Chi_Minh`) và các trường trên. Đối soát dùng `vnp_PayDate` trong response QueryDR nếu có.
   - Test: parse đúng, `vnp_PayDate` lỗi định dạng thì `paid_at` null nhưng vẫn ghi nhận thành công.
4. **Dùng `Clock` (C6).** Các method `mark*` nhận `OffsetDateTime now` từ service (`VnpayIpnProcessor`, `VnpayReconciliationProcessor` đã có hoặc sẽ inject `Clock`). Sửa test domain cho phù hợp.
5. **Đóng đường cũ (C7).**
   - Route `/webhook/momo` và `/webhook/bank`: provider tắt thì trả 404 qua `ApiException` (`PROVIDER_DISABLED`) trước khi parse body.
   - `SecurityException` đổi thành `ApiException` 401 `INVALID_SIGNATURE`.
   - Ghi chú: xoá `processTransaction` khi làm MoMo (M5).
6. **Test contract (C8).** Thêm vào `WebhookControllerTest` một case cho mỗi `RspCode` `00/01/02/04/97/99` (mock use case), kiểm tra HTTP 200 và JSON `{"RspCode","Message"}`.

### Giai đoạn 2: chạy thật trên sandbox (P1)

Làm theo [`VNPAY_SANDBOX_RUNBOOK.md`](../VNPAY_SANDBOX_RUNBOOK.md) và ghi bằng chứng (thời gian, `vnp_TxnRef`, kết quả SQL, không ghi chữ ký) cho bốn kịch bản:

1. Thanh toán thành công: nhận IPN `00`, DB `COMPLETED/SUCCESS`, `paid_at` có giá trị.
2. Khách huỷ trên trang VNPay: IPN `24`, DB `FAILED`, cùng order tạo được payment mới.
3. Mất IPN: tạm đổi IPN URL hoặc tắt tunnel lúc thanh toán; QueryDR phục hồi thành `COMPLETED`.
4. Bỏ dở thanh toán: quá `expired_at` + grace, QueryDR trả `91` và payment chuyển `EXPIRED`. Kịch bản này xác minh giả định về mã `91`.

Lưu payload mẫu đã che chữ ký làm fixture cho test (PRV-06).

### Giai đoạn 3: merchant dùng được kết quả (P2, P3)

1. **API truy vấn (P2).**
   - `GET /api/v1/payments/{paymentId}` và `GET /api/v1/payments?orderId=` trả `paymentId`, `orderId`, `status`, `amount`, `paidAt`, `providerTransactionId`, `failureReason`. Không trả `checkoutUrl` khi payment đã kết thúc.
   - Thêm method truy vấn trong `PaymentService` hoặc một service đọc riêng, cập nhật OpenAPI, viết test controller và service.
   - **Phụ thuộc SEC-03:** không có xác thực thì ai cũng đọc được trạng thái payment. Chỉ bật trên môi trường thật cùng lúc với xác thực merchant.
2. **Return URL (P3).**
   - Verify chữ ký bằng `VnpayIpnVerifier`. Chỉ dùng để hiển thị, vẫn **không** đổi trạng thái.
   - Redirect 302 về `payment.providers.vnpay.merchant-return-url` (cấu hình mới, bắt buộc khi bật VNPay) kèm `paymentId` và `result=processing|success|failed`. Chữ ký sai thì `result=processing`.
   - Frontend merchant gọi API ở bước 1 để lấy trạng thái thật.
   - Test MVC: redirect đúng, không gọi repository ghi.
3. **Thông báo chủ động cho merchant** (outbox → RabbitMQ, EVT-02/03) để sang M4. Trước đó merchant dùng polling.

### Giai đoạn 4: vận hành production (P4, C3, C4, C9)

1. **Lưu IPN (C4).**
   - Bảng `payment_callbacks` (migration mới) gồm `id`, `provider`, `txn_ref`, `params JSONB` (chỉ `vnp_*`, đã bỏ `vnp_SecureHash`), `signature_valid`, `rsp_code`, `received_at`.
   - Ghi trong transaction riêng (`REQUIRES_NEW`), để IPN xử lý lỗi vẫn còn dấu vết. Đặt chính sách lưu trữ có thời hạn.
2. **Hàng đợi xử lý tay (P4).**
   - Thêm cột `manual_action_reason` trên `payments` (`REVIEW_REQUIRED`, `RECONCILIATION_EXHAUSTED`, `LATE_SUCCESS`) và `manual_action_at`. Các chỗ đang log ERROR sẽ ghi thêm cột này.
   - Domain cung cấp:
     - `Payment.approveReview()` → `COMPLETED`;
     - `Payment.rejectReview()` → `FAILED` (kèm yêu cầu hoàn tiền);
     - cách đóng giao dịch exhausted khi đã có kết quả QueryDR hoặc bằng chứng từ VNPay.
   - Trước khi có API admin (cần SEC-03), runbook ghi quy trình SQL có điều kiện `WHERE status = …` và người duyệt.
3. **Nhiều instance (C3).** Trong một transaction ngắn, chọn ứng viên bằng native query `… FOR UPDATE SKIP LOCKED LIMIT :batch` và dời ngay `next_reconciliation_at = now + interval`, rồi commit trước khi gọi QueryDR. Instance khác sẽ không thấy các hàng đó. Không cần ShedLock hay advisory lock.
   - Test Testcontainers: hai luồng claim cùng lúc nhận hai tập hàng rời nhau.
4. **Metric và cảnh báo (C9).** Thêm `spring-boot-starter-actuator` và Micrometer counter `vnpay.ipn{rspCode}`, `vnpay.reconciliation{outcome}`, `payment.manual_action{reason}`. Cảnh báo khi có `LATE_SUCCESS`, `RECONCILIATION_EXHAUSTED`, hoặc tỉ lệ `97` tăng (dấu hiệu bị giả IPN).

## 4. Điều kiện để coi là "dùng được"

- Giai đoạn 1–3 hoàn tất, `./mvnw clean verify` xanh trên GitHub Actions (gồm các test Testcontainers).
- Có bằng chứng sandbox cho cả bốn kịch bản ở giai đoạn 2.
- Merchant đọc được trạng thái cuối qua API và khách được đưa về site merchant.
- Mọi trường hợp cần xử lý tay đều được ghi vào hàng đợi và có quy trình trong runbook. Riêng giai đoạn 4 phải xong trước khi lên production.

## 5. Liên kết roadmap

Chỉ tick tiêu chí trong [`PRODUCTION_READINESS_ROADMAP.md`](../PRODUCTION_READINESS_ROADMAP.md) khi có bằng chứng.

| Mục | Tiêu chí liên quan |
| --- | --- |
| P1 | PRV-04, PRV-06, REF-09 |
| P2 | API-04, API-05, API-09 |
| P3 | WEB-11 (phần Return URL), API-09 |
| P4 | REF-10, SEC-06 |
| C1, C2, C3 | REF-09, REL-* |
| C4 | WEB-08, SEC-06 |
| C5 | REF-09 |
| C7 | WEB-05, WEB-06 (khi làm M5) |
| C8 | WEB-09, WEB-10, WEB-11, TST-* |
| C9 | OBS-* |
| Xác thực cho API truy vấn | SEC-03 |
