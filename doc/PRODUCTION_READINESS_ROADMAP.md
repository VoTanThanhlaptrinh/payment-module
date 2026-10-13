# Production Readiness Roadmap — Payment Module

> **Ngày đánh giá:** 10/10/2026 (đánh giá trước: 04/10/2026)
>
> **Mục tiêu:** Đưa payment module từ nền tảng hiện tại đến trạng thái có thể vận hành production.
>
> **Phạm vi provider:** VNPay, MoMo và chuyển khoản ngân hàng.
>
> **Mô hình thanh toán:** Hosted checkout/QR; hệ thống không nhận, truyền hoặc lưu PAN/CVV.

## Quy tắc theo dõi

- `[x]` chỉ được dùng khi toàn bộ tiêu chí đã đạt và có bằng chứng kiểm chứng được trong repository, CI, dashboard hoặc runbook.
- `[ ]` nghĩa là chưa đạt; phần đã làm dở được ghi tại `Tiến độ hiện tại` nhưng không tính vào tiến độ.
- Tiến độ readiness được tính bằng `số tiêu chí [x] / tổng tiêu chí × 100`; đây không phải ước lượng effort.
- Mỗi PR thay đổi checkbox phải cập nhật đồng thời ngày đánh giá, dashboard và liên kết bằng chứng.
- Tiêu chí chỉ được bỏ khỏi phạm vi bằng một quyết định kiến trúc có lý do; khi đó phải cập nhật lại mẫu số.

## Dashboard

| Mục tiêu | Đã đạt | Tổng | Tiến độ |
| --- | ---: | ---: | ---: |
| ARCH — Kiến trúc và cấu hình | 4 | 7 | 57,1% |
| DAT — Domain và dữ liệu | 3 | 10 | 30,0% |
| API — Payment API | 3 | 9 | 33,3% |
| PRV — Provider integration | 2 | 13 | 15,4% |
| WEB — Webhook/IPN | 4 | 11 | 36,4% |
| REF — Refund và reconciliation | 0 | 10 | 0,0% |
| EVT — Event và tính nhất quán | 0 | 7 | 0,0% |
| SEC — Bảo mật và tuân thủ | 2 | 10 | 20,0% |
| REL — Reliability | 0 | 9 | 0,0% |
| OBS — Observability và SLO | 0 | 9 | 0,0% |
| TST — Testing và quality gate | 4 | 14 | 28,6% |
| OPS — Build, deployment và vận hành | 1 | 12 | 8,3% |
| **Toàn dự án** | **23** | **121** | **19,0%** |

### Release gates

| Gate | Điều kiện | Hiện tại | Trạng thái |
| --- | --- | ---: | --- |
| **P0 — Functional & Secure** | Hoàn tất ARCH, DAT, API, PRV, WEB, SEC và TST-01 đến TST-10 | 22/70 | Chưa đạt |
| **P1 — Operationally Complete** | P0 đạt; hoàn tất REF, EVT, REL, OBS, TST-11 đến TST-14 và OPS-01 đến OPS-09 | 1/48 | Chưa đạt |
| **P2 — Production Approved** | P1 đạt; hoàn tất OPS-10 đến OPS-12, không còn lỗ hổng Critical/High chưa xử lý | 0/3 | Chưa đạt |

Production chỉ được phê duyệt khi đạt cả ba gate và toàn bộ 121 tiêu chí.

## Kết quả đánh giá 10/10/2026

Bản sao working tree (không gồm `.env`) đã được chạy `mvn clean verify` trên JDK 21 với Docker/Testcontainers hoạt động. Đây là lần đầu bốn test PostgreSQL thực sự chạy.

| Bộ test | Kết quả |
| --- | --- |
| 63 unit/MVC test | Pass |
| `PaymentApplicationTests`, `VnpayIpnPersistenceIntegrationTest` | Pass, nhưng trên schema do Hibernate tự tạo, không phải Flyway |
| `FlywayMigrationIntegrationTest` (2 test) | **Fail**: không có `flyway_schema_history`; partial unique index active-payment không tồn tại |

### Phát hiện chặn P0

1. **Flyway không chạy.** Spring Boot 4 tách auto-configuration của Flyway sang module riêng; `pom.xml` chỉ khai báo `flyway-core` nên migration V1/V2 bị bỏ qua hoàn toàn. Schema thực tế do `ddl-auto: update` sinh ra, vì vậy không có partial unique index (`uq_payments_order_id_active`, `uq_transactions_pending_charge`), FK hay index reconciliation. Bằng chứng: log test không có dòng Flyway nào; assertion `expected 4 but was 3`.
2. **Migration lệch entity.** `Transaction.paidAt` map tới cột `paid_at` nhưng V1/V2 không tạo cột này. Khi bật Flyway và `ddl-auto: validate`, context fail với `Schema validation: missing column [paid_at]`.
3. **Production vẫn chạy `ddl-auto: update`.** `application.yaml` đặt `update`; `application-staging.yaml`/`application-production.yaml` không ghi đè. Ghi chú ARCH-05 trước đây nói đã dùng `validate` là sai.
4. **Profile mặc định là `local`.** `spring.profiles.active: local` nằm trong `application.yaml`; quên đặt `SPRING_PROFILES_ACTIVE` khi deploy sẽ chạy với `postgres/postgres` và `guest/guest`.
5. **`.env` được import tự động.** `application.yaml` có `spring.config.import: optional:file:.env[.properties]`, mâu thuẫn với runbook (nói Spring không đọc `.env`). Cần chọn một cách và sửa tài liệu còn lại.
6. **Toàn bộ VNPay slice chưa được commit.** Commit cuối là 04/10/2026; khoảng 50 file (IPN, QueryDR, idempotency, migration, CI workflow, runbook) vẫn là thay đổi chưa commit, nên CI chưa từng chạy trên code này.

Thử nghiệm trên bản sao cho thấy ba thay đổi sau làm toàn bộ **67/67 test pass**: thay `flyway-core` bằng `spring-boot-starter-flyway`, thêm `V3__transactions_paid_at.sql` (`ALTER TABLE transactions ADD COLUMN paid_at TIMESTAMP WITH TIME ZONE;`) và đổi `ddl-auto` thành `validate`.

### Điều chỉnh checkbox

- **DAT-05** bỏ tick: schema migration không khớp entity (`paid_at`).
- **DAT-06** bỏ tick: constraint chỉ tồn tại trong file SQL, không tồn tại trong database khi chạy.
- Không tick thêm tiêu chí nào vì chưa có CI run trên code đã commit.

## Lộ trình đến cổng thanh toán

Thứ tự dưới đây là đường ngắn nhất từ hiện trạng đến một cổng thanh toán VNPay chạy thật, rồi mở rộng sang MoMo/bank. Mỗi mốc liệt kê tiêu chí roadmap mà nó đóng; mốc sau chỉ bắt đầu khi exit gate của mốc trước đạt.

### M0 — Nền móng chạy đúng (đang chặn mọi thứ)

- Commit VNPay slice hiện tại thành các commit Conventional Commits có scope, đẩy lên để CI `verify` chạy.
- Sửa Flyway (starter + V3 `paid_at`), đặt `ddl-auto: validate` cho mọi profile, bỏ `spring.profiles.active` mặc định, thống nhất cách nạp `.env` giữa config và runbook.
- Sửa link SEC-02 trỏ tới `schema-payments.sql` đã xóa.
- **Exit gate:** `./mvnw clean verify` xanh trong GitHub Actions; tick lại DAT-05/06 và xét DAT-07/08/09, ARCH-05, TST-07, TST-14.
- **Tiến độ 10/10/2026:** đã sửa trong working tree: `spring-boot-starter-flyway`, `V3__transactions_paid_at.sql`, `ddl-auto: validate`, `flyway.clean-disabled`, bỏ profile mặc định (`spring-boot:run` dùng `local` qua cấu hình plugin), chỉ profile `local` import `.env`, runbook cập nhật theo. `mvn clean verify` trên bản sao: 67/67 test pass, Flyway áp dụng V1→V3. Còn lại: commit, CI xanh, sửa link SEC-02 (đã sửa trong tài liệu này).

### M1 — VNPay sandbox end-to-end

- Cloudflare Tunnel + credential sandbox theo `VNPAY_SANDBOX_RUNBOOK.md`; checkout thật → IPN `RspCode=00` → DB `COMPLETED/SUCCESS`; thử bỏ IPN để QueryDR phục hồi.
- Bổ sung test mismatch matrix và MVC contract cho từng `RspCode`.
- **Đóng:** PRV-04, PRV-06, WEB-09/10/11 (phần VNPay), REF-09 (VNPay), API-02.

### M2 — API cho merchant và bảo mật nội bộ

- `GET /api/v1/payments/{paymentId}`, `GET /api/v1/payments?orderId=`; authentication/authorization cho API nội bộ (webhook giữ xác thực bằng chữ ký).
- Bảng `webhook_events` để dedupe/audit callback; redact log.
- **Đóng:** API-04, API-05, API-07, API-09, SEC-03, SEC-06, WEB-07, WEB-08.

### M3 — Refund

- `RefundCommand` đầy đủ, refund full/partial có idempotency, `Transaction(type=REFUND)`, VNPay refund API, job hết hạn payment.
- **Đóng:** REF-01 → REF-08, API-06.

### M4 — Sự kiện và tính nhất quán

- Transactional outbox → RabbitMQ, event catalog, consumer idempotent, DLQ. Quyết định giữ hay gỡ Redis (hiện chưa dùng).
- **Đóng:** EVT-01 → EVT-07.

### M5 — MoMo, rồi chuyển khoản ngân hàng

- Trước khi làm: tách lời gọi provider ra khỏi DB transaction trong `PaymentService.createPayment`. VNPay chỉ dựng URL cục bộ nên chấp nhận được, nhưng MoMo gọi HTTP ra ngoài và sẽ giữ advisory lock + connection suốt thời gian chờ.
- Thay đường xử lý webhook chung `verifyWebhook`/`processTransaction` (không đối chiếu amount, ném `SecurityException` thành 500) bằng processor riêng theo mẫu `VnpayIpnProcessor`.
- **Đóng:** PRV-02, PRV-03, PRV-07 → PRV-13, WEB-05, WEB-06, TST-08.

### M6 — Vận hành production

- Observability, reliability, Docker image, staging, backup/restore, runbook sự cố.
- **Đóng:** OBS-*, REL-*, OPS-02 → OPS-12, các tiêu chí SEC và TST còn lại.

## ARCH — Kiến trúc và cấu hình (4/7)

**Kết quả mong muốn:** Boundary rõ ràng, cấu hình theo môi trường và chỉ cho phép capability thực sự được hỗ trợ.

- [x] **ARCH-01** — Mã nguồn được chia thành `domain`, `application`, `adapter`, `infrastructure`, `presentation`. Bằng chứng: [`src/main/java/com/thanh/payment`](../src/main/java/com/thanh/payment).
- [x] **ARCH-02** — Use-case port và provider port tách orchestration khỏi controller/adapter. Bằng chứng: [`application/port`](../src/main/java/com/thanh/payment/application/port).
- [x] **ARCH-03** — Chỉ còn repository port trong domain; interface `application.port.out.PaymentRepository` rỗng đã được loại bỏ. Bằng chứng: [`domain/repository`](../src/main/java/com/thanh/payment/domain/repository).
- [ ] **ARCH-04** — Dependency rule được ghi thành ADR và kiểm tra tự động bằng ArchUnit; domain không phụ thuộc presentation/provider. Bằng chứng đạt: ADR và architecture test chạy trong CI.
- [ ] **ARCH-05** — Có profile `local`, `test`, `staging`, `production`; production không dùng `ddl-auto: update` hoặc `show-sql`. Bằng chứng đạt: cấu hình profile và test binding.
- [ ] **ARCH-06** — Mọi credential, provider secret và broker password đến từ secret store/environment, không có giá trị thật trong Git. Bằng chứng đạt: config placeholder và secret scan pass.
- [x] **ARCH-07** — API từ chối `COD`/`CREDIT_CARD` và mọi method chưa enable trước persistence. Bằng chứng: [`PaymentService.java`](../src/main/java/com/thanh/payment/application/PaymentService.java), [`PaymentServiceTest.java`](../src/test/java/com/thanh/payment/application/PaymentServiceTest.java).

**Tiến độ hiện tại:** Repository port trùng đã được loại bỏ và capability chưa enable bị chặn trước persistence. Đã có profile `local`, `test`, `staging`, `production`, config production không còn secret fallback. **Cập nhật 10/10:** Hibernate vẫn dùng `ddl-auto: update` ở mọi profile và `local` là profile mặc định (xem M0); ARCH-05/06 vẫn chờ sửa cấu hình, profile binding test và secret-scan CI pass.

## DAT — Domain và dữ liệu (3/10)

**Kết quả mong muốn:** Mô hình tiền tệ và lifecycle giao dịch nhất quán, có persistence/migration an toàn và chống cập nhật đồng thời sai.

- [x] **DAT-01** — `Payment` có các trạng thái và bảo vệ transition chính. Bằng chứng: [`Payment.java`](../src/main/java/com/thanh/payment/domain/model/Payment.java).
- [x] **DAT-02** — `Money` chuẩn hóa precision/scale, currency và kiểm tra phép toán khác tiền tệ. Bằng chứng: [`Money.java`](../src/main/java/com/thanh/payment/domain/valueobject/Money.java).
- [x] **DAT-03** — `Transaction` mô hình hóa charge/refund, success/failed/expired và provider transaction ID. Bằng chứng: [`Transaction.java`](../src/main/java/com/thanh/payment/domain/model/Transaction.java).
- [ ] **DAT-04** — Domain chặn order ID không hợp lệ, số tiền không dương, currency/method không hỗ trợ và refund vượt số đã thu. Bằng chứng đạt: invariant tests ở domain/service.
- [ ] **DAT-05** — `Payment` có JPA repository, persistence adapter và schema tương ứng. Bằng chứng: [`PaymentRepository.java`](../src/main/java/com/thanh/payment/domain/repository/PaymentRepository.java), [`V1__payment_schema.sql`](../src/main/resources/db/migration/V1__payment_schema.sql).
- [ ] **DAT-06** — Database giới hạn một payment active trên mỗi order. Bằng chứng: partial unique index trong [`V1__payment_schema.sql`](../src/main/resources/db/migration/V1__payment_schema.sql).
- [ ] **DAT-07** — `Transaction` có schema, repository, index theo payment/provider reference và foreign key hợp lệ. Bằng chứng đạt: migration + integration test.
- [ ] **DAT-08** — Toàn bộ schema được quản lý bằng Flyway/Liquibase với migration tiến/lùi có quy trình rõ ràng. Bằng chứng đạt: versioned migrations và migration test từ database rỗng.
- [ ] **DAT-09** — Payment/transaction dùng optimistic lock hoặc atomic conditional update để chống lost update. Bằng chứng đạt: version column/update condition và concurrency test.
- [ ] **DAT-10** — Có persistence với unique constraint cho idempotency key, webhook event/provider transaction và outbox event. Bằng chứng đạt: migrations + duplicate tests.

**Tiến độ hiện tại:** Đã có Flyway migration, table/repository `Transaction`, FK/index, idempotency table và version columns. **Cập nhật 10/10:** integration test đã chạy trên PostgreSQL và chứng minh Flyway không được thực thi (thiếu auto-config Boot 4) và migration thiếu cột `paid_at`; DAT-05/06 bị bỏ tick cho đến khi `FlywayMigrationIntegrationTest` pass trong CI.

## API — Payment API (3/9)

**Kết quả mong muốn:** API ổn định, idempotent, có thể tạo và truy vấn payment/refund bằng contract được tài liệu hóa.

- [x] **API-01** — Có endpoint tạo payment và Bean Validation cho các trường bắt buộc/số tiền dương. Bằng chứng: [`PaymentController.java`](../src/main/java/com/thanh/payment/api/PaymentController.java), [`CreatePaymentRequest.java`](../src/main/java/com/thanh/payment/api/dto/CreatePaymentRequest.java).
- [ ] **API-02** — `POST /api/v1/payments` bắt buộc `Idempotency-Key`; retry cùng payload trả cùng kết quả, payload khác trả conflict. Bằng chứng đạt: API/service/concurrency tests.
- [x] **API-03** — Create response trả `paymentId`, trạng thái, signed `checkoutUrl`, `expiresAt` và không trả secret; giữ `id` deprecated để tương thích. Bằng chứng: [`PaymentResponse.java`](../src/main/java/com/thanh/payment/api/dto/PaymentResponse.java), [`payment-api.yaml`](../src/main/resources/static/openapi/payment-api.yaml), [`PaymentControllerTest.java`](../src/test/java/com/thanh/payment/api/PaymentControllerTest.java).
- [ ] **API-04** — Có `GET /api/v1/payments/{paymentId}` với authorization và trạng thái hiện tại. Bằng chứng đạt: contract + service/controller tests.
- [ ] **API-05** — Có truy vấn payment/transaction theo `orderId` với pagination khi trả danh sách. Bằng chứng đạt: OpenAPI + repository/controller tests.
- [ ] **API-06** — Có `POST /api/v1/payments/{paymentId}/refunds` hỗ trợ full/partial refund và idempotency. Bằng chứng đạt: contract + E2E sandbox test.
- [ ] **API-07** — Mọi lỗi dùng envelope thống nhất gồm `code`, `message`, `traceId`, `timestamp`, `fieldErrors`; không lộ stack trace. Bằng chứng đạt: `ControllerAdvice` + negative tests.
- [x] **API-08** — Public route có version `/api/v1`. Bằng chứng: [`PaymentController.java`](../src/main/java/com/thanh/payment/api/PaymentController.java), [`WebhookController.java`](../src/main/java/com/thanh/payment/api/WebhookController.java).
- [ ] **API-09** — OpenAPI mô tả request/response, status code, enum, idempotency và ví dụ cho mọi endpoint. Bằng chứng đạt: spec được validate trong CI.

**Tiến độ hiện tại (11/10/2026):** Create API bắt buộc `Idempotency-Key`; Redis giữ key nguyên tử (Lua) làm lớp chặn nhanh, PostgreSQL unique (`uq_idempotency_scope_key`, `uq_payments_order_id_active`) làm lớp bảo vệ cuối, kể cả khi Redis mất dữ liệu; Redis sập trả 503. Đã có unit test và `IdempotencyEndToEndIntegrationTest` (Testcontainers) nhưng chưa chạy trên CI/PostgreSQL thật nên API-02 chưa được tick. Chưa có xác thực nên Idempotency-Key vẫn là không gian chung (SEC-03).

## PRV — Provider integration (2/13)

**Kết quả mong muốn:** Cả ba provider tạo giao dịch thật qua hosted checkout/QR, có mapping ổn định và được kiểm thử với sandbox.

- [x] **PRV-01** — Có `PaymentProvider` abstraction và factory chọn adapter theo method. Bằng chứng: [`PaymentProvider.java`](../src/main/java/com/thanh/payment/application/provider/PaymentProvider.java), [`PaymentProviderFactory.java`](../src/main/java/com/thanh/payment/application/PaymentProviderFactory.java).
- [ ] **PRV-02** — Contract provider dùng typed request/result cho create, query, refund; không dùng `Object` payload ở application boundary. Bằng chứng đạt: interface + compile-time tests.
- [ ] **PRV-03** — HTTP client chung cấu hình connect/read timeout, correlation ID, TLS validation và retry chỉ cho thao tác an toàn. Bằng chứng đạt: config + failure tests.
- [ ] **PRV-04** — VNPay tạo hosted checkout thật, lưu provider reference và expiry. Bằng chứng đạt: sandbox create-payment test.
- [x] **PRV-05** — VNPay mapping success, review-required, declined, timeout và unknown; unknown không mặc định success. Bằng chứng: [`VnpayIpnProcessor.java`](../src/main/java/com/thanh/payment/application/VnpayIpnProcessor.java), [`VnpayIpnProcessorTest.java`](../src/test/java/com/thanh/payment/application/VnpayIpnProcessorTest.java).
- [ ] **PRV-06** — VNPay có contract test chạy với sandbox hoặc deterministic stub theo payload thật. Bằng chứng đạt: test report trong CI.
- [ ] **PRV-07** — MoMo tạo hosted checkout/QR thật, lưu provider reference và expiry. Bằng chứng đạt: sandbox create-payment test.
- [ ] **PRV-08** — MoMo mapping đầy đủ success/pending/declined/timeout và mã lỗi chuẩn nội bộ. Bằng chứng đạt: mapping unit tests.
- [ ] **PRV-09** — MoMo có contract test chạy với sandbox hoặc deterministic stub theo payload thật. Bằng chứng đạt: test report trong CI.
- [ ] **PRV-10** — Bank adapter tạo yêu cầu chuyển khoản/QR thật, lưu reference và expiry. Bằng chứng đạt: sandbox create-payment test.
- [ ] **PRV-11** — Bank adapter mapping đầy đủ success/pending/declined/timeout và mã lỗi chuẩn nội bộ. Bằng chứng đạt: mapping unit tests.
- [ ] **PRV-12** — Bank adapter có contract test chạy với sandbox hoặc deterministic stub theo payload thật. Bằng chứng đạt: test report trong CI.
- [ ] **PRV-13** — Mỗi provider có config validation, sandbox/production switch và runbook rotate/revoke credential. Bằng chứng đạt: startup validation + runbook diễn tập.

**Tiến độ hiện tại:** VNPay đã sinh deterministic signed checkout URL PAY 2.1.0 và có golden fixture; MoMo/bank vẫn disabled. PRV-04 chờ sandbox E2E, PRV-06 chờ CI publish test report.

## WEB — Webhook/IPN (4/11)

**Kết quả mong muốn:** Callback được xác thực theo đúng đặc tả, xử lý đúng một lần về mặt nghiệp vụ và phản hồi đúng contract provider.

- [x] **WEB-01** — Có route nhận VNPay IPN và bind payload. Bằng chứng: [`WebhookController.java`](../src/main/java/com/thanh/payment/api/WebhookController.java).
- [x] **WEB-02** — Có route nhận MoMo IPN và bind payload. Bằng chứng: [`WebhookController.java`](../src/main/java/com/thanh/payment/api/WebhookController.java).
- [x] **WEB-03** — Có route nhận bank webhook và bind payload. Bằng chứng: [`WebhookController.java`](../src/main/java/com/thanh/payment/api/WebhookController.java).
- [x] **WEB-04** — VNPay secure hash được canonicalize và xác minh HMAC-SHA512 constant-time bằng secret từ environment. Bằng chứng: [`VnpaySignatureService.java`](../src/main/java/com/thanh/payment/infrastructure/provider/vnpay/VnpaySignatureService.java), [`VnpayIpnVerifierTest.java`](../src/test/java/com/thanh/payment/infrastructure/provider/vnpay/VnpayIpnVerifierTest.java).
- [ ] **WEB-05** — MoMo signature được canonicalize và xác minh HMAC-SHA256 constant-time. Bằng chứng đạt: official vectors + tamper tests.
- [ ] **WEB-06** — Bank signature được xác minh đúng thuật toán/field order của đối tác. Bằng chứng đạt: official vectors + tamper tests.
- [ ] **WEB-07** — Callback quá hạn hoặc replay bị chặn bằng timestamp/nonce theo khả năng của provider. Bằng chứng đạt: replay tests và audit event.
- [ ] **WEB-08** — Callback trùng provider event/transaction ID không tạo thêm transition hoặc event. Bằng chứng đạt: unique constraint + duplicate tests.
- [ ] **WEB-09** — Webhook đối chiếu order, amount, currency, provider và payment đang chờ trước khi cập nhật. Bằng chứng đạt: mismatch tests.
- [ ] **WEB-10** — Verify, deduplicate, cập nhật payment/transaction và ghi outbox diễn ra atomic dưới concurrent callbacks. Bằng chứng đạt: transaction boundary + concurrency test.
- [ ] **WEB-11** — Success/error acknowledgement đúng contract từng provider; lỗi tạm thời và lỗi vĩnh viễn có response khác nhau. Bằng chứng đạt: controller contract tests.

**Tiến độ hiện tại:** VNPay IPN xác minh signature trước DB, đối chiếu TmnCode/reference/amount/currency/provider, khóa transaction và trả acknowledgement `00/01/02/04/97/99`. Concurrent duplicate integration test đã pass trên PostgreSQL ngày 10/10 (bản sao local, schema do Hibernate tạo); WEB-08/09/10/11 chưa được tick vì cần chạy lại trên schema Flyway trong CI và còn thiếu MoMo/bank.

## REF — Refund và reconciliation (0/10)

**Kết quả mong muốn:** Refund có thể truy vết, không vượt số đã thu và dữ liệu nội bộ luôn được đối chiếu với provider.

- [ ] **REF-01** — `RefundCommand` có payment ID, amount, reason, idempotency key và actor. Bằng chứng đạt: validation + unit tests.
- [ ] **REF-02** — Full refund chỉ chạy với payment đủ điều kiện và chuyển trạng thái đúng. Bằng chứng đạt: domain/service tests.
- [ ] **REF-03** — Partial refund cho phép nhiều lần nhưng tổng refund không vượt captured amount. Bằng chứng đạt: invariant + concurrency tests.
- [ ] **REF-04** — Mỗi refund tạo `Transaction(type=REFUND)` và lưu provider reference, trạng thái, lỗi. Bằng chứng đạt: persistence integration test.
- [ ] **REF-05** — VNPay, MoMo và bank thực hiện refund/query refund theo capability thực tế hoặc có policy manual được duyệt. Bằng chứng đạt: provider contract tests/runbook.
- [ ] **REF-06** — Retry refund cùng idempotency key không gửi yêu cầu provider lần hai. Bằng chứng đạt: duplicate/concurrency tests.
- [ ] **REF-07** — API truy vấn được refund history và trạng thái cuối cùng. Bằng chứng đạt: OpenAPI + E2E test.
- [ ] **REF-08** — Job đánh dấu payment/transaction hết hạn an toàn và phát sự kiện tương ứng. Bằng chứng đạt: clock-controlled integration test.
- [ ] **REF-09** — Reconciliation định kỳ so sánh local/provider, phát hiện missing/amount/status mismatch. Bằng chứng đạt: scheduled job + fixture report.
- [ ] **REF-10** — Có queue/quy trình xử lý sai lệch, quyền phê duyệt và audit cho thao tác sửa thủ công. Bằng chứng đạt: runbook + audit test.

**Tiến độ hiện tại:** VNPay đã có QueryDR signed request/response và scheduler cho charge `PENDING` quá hạn, retry 5 phút tối đa 12 lần. REF-09 chưa được tick vì migration/scheduler integration test và sandbox recovery evidence chưa chạy.

## EVT — Event và tính nhất quán (0/7)

**Kết quả mong muốn:** Thay đổi trạng thái và phát sự kiện không bị lệch; consumer xử lý trùng/lỗi an toàn.

- [ ] **EVT-01** — Có event catalog và versioned schema cho created, completed, failed, expired, refunded. Bằng chứng đạt: tài liệu contract + compatibility test.
- [ ] **EVT-02** — Outbox event được ghi cùng transaction database với payment/transaction. Bằng chứng đạt: rollback/atomicity integration test.
- [ ] **EVT-03** — Publisher gửi outbox lên RabbitMQ và đánh dấu published có retry an toàn. Bằng chứng đạt: broker integration test.
- [ ] **EVT-04** — Consumer dùng event ID/idempotency key để tránh side effect trùng. Bằng chứng đạt: redelivery test.
- [ ] **EVT-05** — Có retry policy, DLQ, cảnh báo và công cụ replay có audit. Bằng chứng đạt: failure drill/runbook.
- [ ] **EVT-06** — Ordering theo payment ID và correlation/causation ID được bảo toàn hoặc xử lý rõ ràng. Bằng chứng đạt: out-of-order tests.
- [ ] **EVT-07** — Redis có use case, TTL, consistency và failure policy được test; nếu không cần thì dependency/config được gỡ. Bằng chứng đạt: ADR + integration test hoặc removal diff.

## SEC — Bảo mật và tuân thủ (2/10)

**Kết quả mong muốn:** Giảm phạm vi PCI bằng hosted checkout, bảo vệ secret/dữ liệu và lưu audit không thể chối bỏ.

- [x] **SEC-01** — Public payment/webhook DTO hiện không nhận PAN, CVV hoặc dữ liệu thẻ thô. Bằng chứng: [`api/dto`](../src/main/java/com/thanh/payment/api/dto), [`dto/webhook`](../src/main/java/com/thanh/payment/api/dto/webhook).
- [x] **SEC-02** — Entity/schema hiện không có cột PAN/CVV. Bằng chứng: [`domain/model`](../src/main/java/com/thanh/payment/domain/model), [`V1__payment_schema.sql`](../src/main/resources/db/migration/V1__payment_schema.sql).
- [ ] **SEC-03** — Internal API có authentication và authorization theo service/user scope; webhook chỉ dựa vào verified provider identity/signature. Bằng chứng đạt: security config + authorization tests.
- [ ] **SEC-04** — TLS bắt buộc cho inbound/outbound; certificate/hostname validation không bị tắt. Bằng chứng đạt: deployment config + TLS tests.
- [ ] **SEC-05** — Secret được lưu trong secret manager, phân quyền tối thiểu, rotate/revoke có diễn tập. Bằng chứng đạt: policy + rotation record.
- [ ] **SEC-06** — Log, trace, metric và error response được redact token, signature, secret và PII. Bằng chứng đạt: automated log-capture tests.
- [ ] **SEC-07** — Dữ liệu nhạy cảm được mã hóa at rest/in transit; retention/deletion policy được tài liệu hóa. Bằng chứng đạt: infrastructure policy + verification.
- [ ] **SEC-08** — Audit trail ghi actor, action, before/after status, correlation ID và không cho sửa qua API. Bằng chứng đạt: audit integration tests.
- [ ] **SEC-09** — CI chạy secret scan, SAST, dependency/container scan; không còn Critical/High chưa có exception được duyệt. Bằng chứng đạt: CI reports.
- [ ] **SEC-10** — Threat model bao phủ spoofing webhook, replay, IDOR, SSRF/egress, injection, fraud và abuse. Bằng chứng đạt: threat model được review + security tests.

## REL — Reliability (0/9)

**Kết quả mong muốn:** Lỗi provider/dependency không gây treo request, duplicate charge hoặc mất khả năng phục hồi.

- [ ] **REL-01** — Mọi provider/database/broker call có connect/read/overall timeout được cấu hình và test. Bằng chứng đạt: fault-injection tests.
- [ ] **REL-02** — Retry dùng exponential backoff+jitter, chỉ áp dụng thao tác idempotent và có giới hạn. Bằng chứng đạt: retry policy tests.
- [ ] **REL-03** — Circuit breaker/bulkhead cô lập từng provider; trạng thái mở được quan sát. Bằng chứng đạt: failure tests + metrics.
- [ ] **REL-04** — Rate limit/quota bảo vệ create, refund, query và webhook mà không chặn callback hợp lệ. Bằng chứng đạt: load/rate-limit tests.
- [ ] **REL-05** — Liveness/readiness kiểm tra đúng dependency bắt buộc và không gây restart loop khi provider lỗi. Bằng chứng đạt: probe tests.
- [ ] **REL-06** — Graceful shutdown ngừng nhận request, hoàn tất transaction đang chạy và không mất outbox event. Bằng chứng đạt: shutdown test.
- [ ] **REL-07** — Service stateless, chạy nhiều replica và xử lý concurrent request nhất quán. Bằng chứng đạt: multi-instance concurrency test.
- [ ] **REL-08** — Có degradation policy cho PostgreSQL, RabbitMQ, Redis và từng provider bị gián đoạn. Bằng chứng đạt: documented matrix + game-day record.
- [ ] **REL-09** — Capacity/load test chứng minh headroom tối thiểu 2× peak dự kiến mà vẫn giữ SLO. Bằng chứng đạt: versioned performance report.

## OBS — Observability và SLO (0/9)

**Kết quả mong muốn:** Có thể phát hiện, khoanh vùng và xử lý sự cố thanh toán bằng metric, trace, log và alert có hành động.

- [ ] **OBS-01** — Log JSON có timestamp, level, service, environment, trace/correlation ID, payment ID đã kiểm soát. Bằng chứng đạt: log schema test.
- [ ] **OBS-02** — Log không chứa secret, signature, token, PAN/CVV hoặc payload nhạy cảm. Bằng chứng đạt: redaction tests + log sampling review.
- [ ] **OBS-03** — Có metric request rate/error/duration và JVM/database pool/broker health. Bằng chứng đạt: metrics endpoint + dashboard.
- [ ] **OBS-04** — Có metric nghiệp vụ theo provider/method/status: create, success, failure, timeout, duplicate, refund, reconciliation mismatch. Bằng chứng đạt: metric tests + dashboard.
- [ ] **OBS-05** — Distributed trace nối API, database, provider call, webhook và event bằng correlation ID. Bằng chứng đạt: staging trace mẫu.
- [ ] **OBS-06** — Dashboard thể hiện conversion rate, provider latency/error và backlog outbox/DLQ/reconciliation. Bằng chứng đạt: versioned dashboard.
- [ ] **OBS-07** — Alert có ngưỡng, severity, owner và runbook; được test bằng alert drill. Bằng chứng đạt: alert rules + drill record.
- [ ] **OBS-08** — SLI chứng minh availability ≥99,9% và p95 API nội bộ <500 ms, loại riêng latency chờ provider. Bằng chứng đạt: rolling report và load test.
- [ ] **OBS-09** — Có error budget, review định kỳ và policy dừng release khi burn rate vượt ngưỡng. Bằng chứng đạt: SLO policy + review record.

## TST — Testing và quality gate (4/14)

**Kết quả mong muốn:** Test tự động chứng minh correctness, compatibility, security và khả năng chịu tải trước khi release.

- [x] **TST-01** — Có unit test state transition của `Payment`. Bằng chứng: [`PaymentTest.java`](../src/test/java/com/thanh/payment/domain/model/PaymentTest.java).
- [x] **TST-02** — Có unit test lifecycle của `Transaction`. Bằng chứng: [`TransactionTest.java`](../src/test/java/com/thanh/payment/domain/model/TransactionTest.java).
- [x] **TST-03** — Có unit test phép toán và invariant của `Money`. Bằng chứng: [`MoneyTest.java`](../src/test/java/com/thanh/payment/domain/valueobject/MoneyTest.java).
- [x] **TST-04** — Có Spring context test và Testcontainers config cho PostgreSQL/Redis. Bằng chứng: [`PaymentApplicationTests.java`](../src/test/java/com/thanh/payment/PaymentApplicationTests.java), [`TestcontainersConfiguration.java`](../src/test/java/com/thanh/payment/TestcontainersConfiguration.java).
- [ ] **TST-05** — `PaymentService` có unit test create/process/refund, duplicate, provider failure và rollback. Bằng chứng đạt: deterministic unit tests.
- [ ] **TST-06** — Controller test bao phủ validation, authorization, status code và error envelope. Bằng chứng đạt: MVC tests.
- [ ] **TST-07** — Repository/migration test chạy trên PostgreSQL thật, kiểm tra constraints, indexes và rollback. Bằng chứng đạt: Testcontainers reports.
- [ ] **TST-08** — Cả ba provider có mapping/contract test với signed fixtures và sandbox/stub. Bằng chứng đạt: CI contract-test stage.
- [ ] **TST-09** — Webhook tests bao phủ valid/tampered/replayed/duplicate/mismatch/concurrent callback. Bằng chứng đạt: automated suite.
- [ ] **TST-10** — E2E test bao phủ create → hosted checkout stub → webhook → query và full/partial refund. Bằng chứng đạt: staging/sandbox test report.
- [ ] **TST-11** — Concurrency/idempotency tests chứng minh không duplicate payment, charge, refund hoặc event. Bằng chứng đạt: repeated parallel test report.
- [ ] **TST-12** — Performance, resilience và security tests chạy với ngưỡng pass/fail rõ ràng. Bằng chứng đạt: versioned reports.
- [ ] **TST-13** — Coverage gate đạt ≥80% line, ≥70% branch toàn module và ≥90% cho domain/service cốt lõi. Bằng chứng đạt: JaCoCo report + CI gate.
- [ ] **TST-14** — `./mvnw clean verify` chạy thành công trong CI với JDK pin version và Docker/Testcontainers. Bằng chứng đạt: protected-branch CI run.

**Lưu ý baseline (10/10/2026):** 67 test. 63 unit/MVC test pass; khi chạy với Docker, 2 test context/IPN concurrency pass và 2 test `FlywayMigrationIntegrationTest` fail do Flyway không chạy (xem phần đánh giá 10/10). TST-14 chỉ được tick sau khi `clean verify` pass trong CI.

## OPS — Build, deployment và vận hành (1/12)

**Kết quả mong muốn:** Artifact có thể tái tạo, triển khai/rollback an toàn và hệ thống có kế hoạch khôi phục đã diễn tập.

- [x] **OPS-01** — Maven Wrapper được commit với Maven distribution version cố định. Bằng chứng: [`mvnw`](../mvnw), [`maven-wrapper.properties`](../.mvn/wrapper/maven-wrapper.properties).
- [ ] **OPS-02** — JDK, dependency, plugin và container image dùng version/policy cập nhật có kiểm soát; không dùng tag `latest`. Bằng chứng đạt: build config + dependency update policy.
- [ ] **OPS-03** — Có multi-stage Dockerfile, chạy non-root, filesystem tối thiểu/read-only khi khả thi và image healthcheck. Bằng chứng đạt: image tests + container scan.
- [ ] **OPS-04** — Container nhận config/secret bên ngoài và kết nối PostgreSQL/Redis/RabbitMQ managed/external. Bằng chứng đạt: deployment template + smoke test.
- [ ] **OPS-05** — CI chạy compile, test, coverage, lint/static analysis, migration, contract và security gates trên mọi PR. Bằng chứng đạt: required branch checks.
- [ ] **OPS-06** — Artifact/image được version hóa bất biến, tạo SBOM, ký và lưu provenance. Bằng chứng đạt: registry metadata + verification job.
- [ ] **OPS-07** — Có staging gần tương đương production; deploy tự động chạy migration check, smoke test và sandbox payment. Bằng chứng đạt: successful deployment record.
- [ ] **OPS-08** — Migration áp dụng backward-compatible theo expand/migrate/contract và có rollback/roll-forward plan. Bằng chứng đạt: staging rehearsal.
- [ ] **OPS-09** — Release strategy hỗ trợ rollback nhanh, graceful drain và không làm mất webhook/event đang xử lý. Bằng chứng đạt: rollback rehearsal.
- [ ] **OPS-10** — Backup PostgreSQL và cấu hình được mã hóa; restore drill chứng minh **RPO ≤5 phút, RTO ≤60 phút**. Bằng chứng đạt: dated restore report.
- [ ] **OPS-11** — Có runbook cho provider outage, signature failure, payment stuck, DLQ, reconciliation mismatch, secret rotation và data recovery. Bằng chứng đạt: reviewed runbooks + game-day record.
- [ ] **OPS-12** — Production approval ghi nhận owner/on-call, capacity, SLO, threat model, không còn Critical/High và sign-off release. Bằng chứng đạt: release checklist được phê duyệt.

## Contract mục tiêu

Các contract sau là đích đến và chưa có nghĩa đã được triển khai:

| Interface | Yêu cầu tối thiểu |
| --- | --- |
| `POST /api/v1/payments` | Header `Idempotency-Key`; request gồm order, amount, currency, method; response gồm payment ID, status, hosted checkout URL/QR và expiry. |
| `GET /api/v1/payments/{paymentId}` | Trả payment, provider method, trạng thái, amount/currency, expiry và transaction/refund summary theo quyền truy cập. |
| `GET /api/v1/payments?orderId=...` | Trả danh sách có pagination, sắp xếp ổn định và không lộ dữ liệu provider nhạy cảm. |
| `POST /api/v1/payments/{paymentId}/refunds` | Header `Idempotency-Key`; request gồm amount/reason; response gồm refund/transaction ID, status và provider reference an toàn. |
| Webhook VNPay/MoMo/bank | Giữ route version hiện tại; xác thực signature/replay, deduplicate và trả acknowledgement đúng đặc tả từng provider. |
| Error envelope | `code`, `message`, `traceId`, `timestamp`, `fieldErrors`; không trả stack trace/secret. |

API production chỉ chấp nhận `VNPAY`, `MOMO`, `BANK_TRANSFER`. `COD` và `CREDIT_CARD` phải bị từ chối trước persistence cho đến khi có capability được duyệt. Hosted checkout/tokenization thuộc provider; module không xử lý dữ liệu thẻ thô.

## Persistence mục tiêu

- `payments`: aggregate status, amount/currency, method, expiry, version, timestamps và idempotency reference.
- `transactions`: charge/refund, provider reference, amount/currency, status, failure code/reason và completion timestamps.
- `webhook_events`: provider event/transaction key duy nhất, payload hash, receive/process status và audit metadata; không lưu payload nhạy cảm không cần thiết.
- `idempotency_keys`: scope, request hash, response reference, trạng thái và TTL phù hợp với thời gian retry nghiệp vụ.
- `outbox_events`: event ID/type/version, aggregate ID, payload đã kiểm soát, created/published timestamps và retry metadata.

Mọi thay đổi schema phải đi qua migration có version, unique/index/foreign key rõ ràng và integration test trên PostgreSQL.

## Cách cập nhật tài liệu

1. Hoàn tất implementation và test của tiêu chí.
2. Gắn bằng chứng cụ thể; không dùng mô tả “đã làm” không có link/report.
3. Đổi `[ ]` thành `[x]`, cập nhật số liệu nhóm, dashboard và release gate.
4. Chạy kiểm tra Markdown, đếm lại checkbox và `git diff --check`.
5. Ghi ngày đánh giá mới trong cùng PR.
