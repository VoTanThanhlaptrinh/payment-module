# Kế hoạch hôm nay — Hoàn thiện VNPay Vertical Slice

> **Ngày:** 04/10/2026
>
> **Thời lượng dự kiến:** 8 giờ
>
> **Mục tiêu:** VNPay code-complete với checkout URL, HMAC-SHA512, transaction persistence, IPN và deterministic tests.
>
> **Giới hạn:** Chưa có sandbox credential/public HTTPS callback nên chưa thể công nhận sandbox end-to-end hoặc production-ready.

## Cách theo dõi

- Chỉ đổi `[ ]` thành `[x]` khi tiêu chí đạt đầy đủ và đã gắn bằng chứng.
- Tiến độ bằng `số tiêu chí [x] / 37 × 100`; không dùng công việc làm dở để tính điểm.
- Cập nhật đồng thời dashboard, ngày đánh giá và phần bằng chứng khi thay đổi checkbox.
- Nếu test chưa chạy pass thì tiêu chí triển khai liên quan chưa được xem là hoàn tất.

## Dashboard đầu ngày

| Khung thời gian | Nhóm công việc | Đã đạt | Tổng | Trạng thái |
| --- | --- | ---: | ---: | --- |
| 0:00–0:30 | Preflight và khóa contract | 2 | 4 | Đang thực hiện |
| 0:30–1:45 | Transaction persistence | 0 | 5 | Chưa bắt đầu |
| 1:45–2:45 | Provider contract và cấu hình | 0 | 4 | Chưa bắt đầu |
| 2:45–4:15 | Checkout URL | 0 | 6 | Chưa bắt đầu |
| 4:15–5:45 | IPN | 0 | 7 | Chưa bắt đầu |
| 5:45–7:15 | Test bắt buộc | 0 | 7 | Chưa bắt đầu |
| 7:15–8:00 | Nghiệm thu và cập nhật | 0 | 4 | Chưa bắt đầu |
| **Tổng** |  | **2** | **37** | **5,4%** |

## Definition of Done cuối ngày

- Hoàn thành 37/37 tiêu chí và `./mvnw clean verify` chạy thành công trên JDK 17.
- Không còn logic VNPay chấp nhận chữ ký chỉ vì chuỗi không rỗng.
- Create payment trả checkout URL/expiry; valid IPN cập nhật đồng thời payment và transaction.
- Tampered, mismatch, missing và duplicate IPN đều có test và không gây cập nhật sai.
- Không commit credential hoặc secret; không ghi signature/hash secret vào log.
- Ghi rõ sandbox smoke test vẫn blocked cho đến khi có credential và public HTTPS callback.

## 0:00–0:30 — Preflight và khóa contract (2/4)

- [ ] **VN-01** — Xác minh JDK 17, Maven Wrapper và Docker; chạy baseline test. Bằng chứng: output `java -version`, `docker version`, `./mvnw test` được lưu trong PR/issue. **Blocker hiện biết:** môi trường Codex chưa có Java.
- [ ] **VN-02** — Gửi yêu cầu cấp VNPay sandbox `TmnCode`/hash secret và ghi owner, thời điểm, trạng thái chờ. Bằng chứng: ticket/email tham chiếu; tuyệt đối không chép secret vào tài liệu.
- [x] **VN-03** — Pin PAY API `2.1.0`, HMAC-SHA512, VND, timezone `Asia/Ho_Chi_Minh`, locale `vn`, order type `other`, expiry 15 phút. Bằng chứng: [tài liệu PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html) và [hướng dẫn checksum](https://sandbox.vnpayment.vn/apis/docs/chuyen-doi-thuat-toan/changeTypeHash.html).
- [x] **VN-04** — Chốt dùng `Transaction.id` làm `vnp_TxnRef`, không dùng `orderId`. Bằng chứng: quyết định trong kế hoạch này; implementation phải được test ở VN-29/VN-32.

**Exit gate:** Toolchain sẵn sàng hoặc blocker được ghi rõ; contract không còn quyết định mở.

## 0:30–1:45 — Transaction persistence (0/5)

- [ ] **VN-05** — Bổ sung schema `transactions` với FK tới `payments`, index theo `payment_id`, unique/index phù hợp cho provider reference. Bằng chứng: schema/migration và database integration test.
- [ ] **VN-06** — Tạo transaction repository hỗ trợ lưu, tìm theo transaction ID và tìm charge `PENDING` theo payment. Bằng chứng: repository test trên PostgreSQL.
- [ ] **VN-07** — Tạo và lưu charge transaction trước khi dựng checkout URL để có merchant reference duy nhất. Bằng chứng: service test xác nhận `vnp_TxnRef == Transaction.id`.
- [ ] **VN-08** — Success/failure callback cập nhật `Payment` và `Transaction` trong cùng database transaction; lỗi persistence rollback cả hai. Bằng chứng: integration test.
- [ ] **VN-09** — Request lặp cho order đang pending tái sử dụng charge chưa hết hạn, không tạo transaction hoặc checkout attempt thứ hai. Bằng chứng: duplicate/concurrency service test.

**Exit gate:** Có thể tạo, đọc và cập nhật charge transaction; callback không thể tạo trạng thái payment/transaction lệch nhau.

## 1:45–2:45 — Provider contract và cấu hình (0/4)

- [ ] **VN-10** — Thay contract `processPayment(Payment): void` bằng typed create request/result; result chứa `transactionId`, `checkoutUrl`, `expiresAt`, normalized status. Bằng chứng: compile-time API và unit tests.
- [ ] **VN-11** — `PaymentResponse` trả `transactionId`, `checkoutUrl`, `expiresAt` mà không lộ provider secret. Bằng chứng: mapper/controller test.
- [ ] **VN-12** — Thêm validated properties `payment.providers.vnpay.enabled`, `payment-url`, `return-url`, `tmn-code`, `hash-secret`, `version`, `expiry`; fail-fast khi bật mà thiếu/sai. Bằng chứng: configuration tests.
- [ ] **VN-13** — `TmnCode`/secret chỉ lấy từ environment; chỉ VNPay được enable, MoMo/bank/COD/credit card bị từ chối trước persistence. Bằng chứng: config scan và negative service tests.

**Exit gate:** Controller nhận typed checkout result; không thể tạo payment bằng adapter chưa sẵn sàng.

## 2:45–4:15 — Checkout URL (0/6)

- [ ] **VN-14** — Chỉ chấp nhận VND nguyên, tối thiểu 5.000 VND và không vượt giới hạn `vnp_Amount`; validation xảy ra trước khi ghi database. Bằng chứng: boundary tests. [VNPay xác nhận VND và mức tối thiểu](https://sandbox.vnpayment.vn/apis/docs/faqs/).
- [ ] **VN-15** — URL chứa đủ `vnp_Version`, `vnp_Command`, `vnp_TmnCode`, `vnp_Amount`, `vnp_CurrCode`, `vnp_TxnRef`, `vnp_OrderInfo`, `vnp_OrderType`, `vnp_ReturnUrl`, `vnp_IpAddr`, `vnp_CreateDate`, `vnp_ExpireDate`, `vnp_Locale`. Bằng chứng: exact parameter test.
- [ ] **VN-16** — Amount được nhân chính xác 100; create/expire time dùng GMT+7 và expiry đúng 15 phút. Bằng chứng: fixed-clock tests.
- [ ] **VN-17** — Parameters được sort, UTF-8 encode và ký HMAC-SHA512; không gửi `vnp_SecureHashType` ở phiên bản 2.1.0. Bằng chứng: golden hash/URL fixture.
- [ ] **VN-18** — Dùng injected `Clock`; client IP lấy server-side và không tin forwarded header nếu trusted proxy chưa cấu hình. Bằng chứng: deterministic unit/MVC tests.
- [ ] **VN-19** — Return URL chỉ phục vụ UX, không cập nhật payment; log không chứa hash secret/signature hoặc full checkout URL nhạy cảm. Bằng chứng: code review và log-capture test.

**Exit gate:** Cùng input, clock và test secret luôn sinh đúng một canonical string/hash/URL dự kiến.

## 4:15–5:45 — IPN (0/7)

- [ ] **VN-20** — `GET /api/v1/payments/webhook/vnpay` nhận toàn bộ raw query parameters; loại `vnp_SecureHash` và `vnp_SecureHashType` trước khi canonicalize. Bằng chứng: controller/adapter test có trường mở rộng.
- [ ] **VN-21** — Recompute HMAC-SHA512 và so sánh constant-time; thiếu/sai signature bị từ chối trước database lookup/update. Bằng chứng: valid/tampered/missing signature tests.
- [ ] **VN-22** — Đối chiếu TmnCode, transaction reference, payment method, stored VND amount ×100 và trạng thái trước update. Bằng chứng: mismatch matrix tests.
- [ ] **VN-23** — Chỉ cặp `vnp_ResponseCode=00` và `vnp_TransactionStatus=00` là success; pending/failed/unknown được mapping tường minh và không mặc định success. Bằng chứng: table-driven mapping tests.
- [ ] **VN-24** — Lưu `vnp_TransactionNo` vào transaction và failure code/reason khi giao dịch không thành công. Bằng chứng: persistence assertions.
- [ ] **VN-25** — Luôn trả HTTP 200 với JSON đúng: `00` processed, `02` already confirmed, `01` not found, `04` amount mismatch, `97` invalid signature, `99` unexpected/input error. Bằng chứng: MVC contract tests. [Quy ước IPN chính thức](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
- [ ] **VN-26** — Callback lặp không thực hiện transition hoặc side effect lần hai và trả `02` khi giao dịch đã xác nhận. Bằng chứng: sequential và concurrent duplicate tests.

**Exit gate:** Mọi IPN đều được xác minh trước xử lý và cho kết quả deterministic, idempotent ở mức state transition.

## 5:45–7:15 — Test bắt buộc (0/7)

- [ ] **VN-27** — Golden fixture xác nhận chính xác canonical string, HMAC và checkout URL với fixed clock/test secret.
- [ ] **VN-28** — Tests bao phủ encoding/field order, amount ×100, VND/min/max và cấu hình thiếu/sai.
- [ ] **VN-29** — Tests bao phủ create response, transaction persistence, `Transaction.id` reference, reuse pending charge và rollback khi URL generation lỗi.
- [ ] **VN-30** — Table-driven tests bao phủ provider status success, pending, failed và unknown.
- [ ] **VN-31** — IPN tests bao phủ valid, tampered, wrong TmnCode/reference/amount, missing field và duplicate callback.
- [ ] **VN-32** — MVC tests xác nhận checkout response và từng `RspCode`/`Message` của endpoint IPN.
- [ ] **VN-33** — Unit, MVC và PostgreSQL Testcontainers pass; `./mvnw clean verify` kết thúc thành công trên JDK 17. Bằng chứng: test report/CI log.

**Exit gate:** Không chấp nhận test bị skip, flaky hoặc chỉ dựa vào artifact cũ trong `target/`.

## 7:15–8:00 — Nghiệm thu và cập nhật (0/4)

- [ ] **VN-34** — Chạy demo fixture: create payment → nhận checkout URL → gửi signed IPN → payment/transaction chuyển trạng thái đúng. Bằng chứng: sanitized request/response và database assertions.
- [ ] **VN-35** — Secret scan và log review không phát hiện credential, hash secret hoặc signature; `git diff --check` sạch. Bằng chứng: command outputs.
- [ ] **VN-36** — Cập nhật `PRODUCTION_READINESS_ROADMAP.md` chỉ cho tiêu chí có bằng chứng và tính lại dashboard/gates.
- [ ] **VN-37** — Ghi handoff sandbox gồm biến môi trường, public HTTPS IPN/Return URL, smoke-test steps và blocker owner; không ghi secret. Bằng chứng: mục “Sandbox handoff” hoàn chỉnh bên dưới.

## Tác động dự kiến lên Production Roadmap

Khi toàn bộ test liên quan pass, có thể xem xét tick:

- `API-03`: create response có checkout URL và expiry.
- `PRV-05`: mapping VNPay được kiểm thử.
- `PRV-06`: deterministic contract fixture đạt yêu cầu.
- `WEB-04`: HMAC-SHA512 thật và tamper tests.
- `DAT-07`: chỉ khi schema/repository/FK/index và PostgreSQL integration test đều đầy đủ.

Không tick `PRV-04` trước khi có sandbox smoke test thật. Cũng không tick các tiêu chí áp dụng cho toàn bộ provider như `PRV-02`, `PRV-03`, `PRV-13`, `TST-08`, `TST-09` hoặc các tiêu chí outbox/reconciliation chưa triển khai.

| Trường hợp | Tiến độ roadmap dự kiến |
| --- | ---: |
| Bốn tiêu chí VNPay code/test đạt | 24/121 — 19,8% |
| Thêm `DAT-07` đạt đầy đủ | 25/121 — 20,7% |
| Sau này thêm sandbox `PRV-04` | 26/121 — 21,5% |

## Ngoài phạm vi hôm nay

- VNPay refund/queryDR và reconciliation.
- Transactional outbox, RabbitMQ, Redis và event delivery.
- Hoàn thiện MoMo, bank transfer, COD hoặc credit card.
- Authentication/authorization, observability, performance và deployment production.
- Công nhận provider production-ready khi chưa có sandbox/production credential.

## Sandbox handoff

Trạng thái ban đầu: **Blocked — chưa có credential và public HTTPS callback**.

Thông tin cần bổ sung, không ghi giá trị secret vào Git:

- `VNPAY_TMN_CODE`: _chưa có_
- `VNPAY_HASH_SECRET`: _chưa có_
- `VNPAY_PAYMENT_URL`: `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`
- Public Return URL: _chưa có_
- Public IPN URL trỏ tới `/api/v1/payments/webhook/vnpay`: _chưa có_
- Owner đăng ký/cấu hình: _chưa gán_
- Link ticket/email sandbox: _chưa có_

Khi credential được cấp: inject qua environment/secret store, tạo một payment VND hợp lệ, hoàn tất checkout sandbox, xác nhận IPN hợp lệ cập nhật đúng transaction/payment, lưu sanitized evidence rồi mới tick `PRV-04`.

## Lệnh nghiệm thu

```bash
java -version
docker version
./mvnw clean verify
git diff --check
```

Trên Windows, thay `./mvnw` bằng `mvnw.cmd`.
