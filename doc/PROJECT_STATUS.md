# Báo cáo tiến độ Payment Module

> **Thời điểm đánh giá:** 04/10/2026  
> **Phạm vi:** Snapshot hiện tại của repository, đối chiếu ở hai mức MVP backend và production-ready.

## Cách đọc báo cáo

- **Đã có:** Năng lực cốt lõi đã được triển khai trong code.
- **Một phần:** Đã có mô hình hoặc luồng chính nhưng còn giả lập, thiếu kết nối, persistence hoặc kiểm thử cần thiết.
- **Chưa có:** Chưa thấy triển khai trong repository.

Dự án sử dụng Java 17, Spring Boot, Maven, PostgreSQL, Redis, RabbitMQ, JPA, Lombok, JUnit 5 và Testcontainers. Cấu trúc package là một context chia lớp như job-backend: `api`, `application`, `domain`, `mapper`, `shared` và `infrastructure` (mỗi provider một package con), phù hợp với hướng dẫn trong `AGENTS.md`.

## Kết quả đã đạt được

- Đã xây dựng domain `Payment`, `Transaction`, value object `Money`, tiền tệ, phương thức thanh toán và các trạng thái nghiệp vụ. Các quy tắc hoàn tất, thất bại, hoàn tiền và hết hạn được đóng gói trong entity.
- Đã có persistence cho `Payment`, `Transaction` và idempotency key. Flyway migration tạo FK, indexes, active-payment constraint và optimistic-lock version columns.
- `POST /api/v1/payments` bắt buộc `Idempotency-Key`, validate VNPay/VND trước persistence và trả `paymentId`, transaction, signed checkout URL cùng expiry.
- VNPay PAY 2.1.0 đã dựng URL deterministic, amount ×100, timezone GMT+7, sorted parameters và HMAC-SHA512; MoMo/bank vẫn disabled.
- VNPay IPN xác minh HMAC constant-time trước database, đối chiếu merchant/reference/amount/provider, khóa transaction và trả acknowledgement theo contract.
- VNPay QueryDR fallback đã có signed request/response, scheduler 5 phút, giới hạn 12 lần và không ghi đè transaction terminal; có runbook Cloudflare Quick Tunnel cho sandbox IPN.
- Có 67 test method. 63 unit/MVC test đã chạy pass; bốn context/migration/concurrency test chờ Docker/Testcontainers.
- OpenAPI đã được Redocly validate; workflow CI chạy JDK 17, Maven verify, OpenAPI lint và secret scan.
- Lịch sử Git dùng Conventional Commits có scope, thể hiện các mốc domain, kiến trúc, database, provider và webhook tương đối rõ ràng.

## Ma trận mức độ hoàn thiện

| Năng lực | MVP backend | Production-ready | Hiện trạng |
| --- | --- | --- | --- |
| Domain payment/transaction | Đã có | Một phần | Có entity, value object và state transition; domain vẫn phụ thuộc annotation JPA. |
| API thanh toán | Một phần | Chưa có | Mới có tạo payment; chưa có API lấy trạng thái, lịch sử, hủy hoặc refund. |
| Database | Một phần | Chưa có | Có Flyway schema/repository cho payment, transaction và idempotency; integration test PostgreSQL chưa chạy. |
| Tích hợp provider | Một phần | Chưa có | VNPay tạo signed hosted-checkout URL và signed QueryDR fallback; chưa có IPN sandbox E2E, refund, MoMo hoặc bank. |
| Webhook/IPN | Một phần | Chưa có | VNPay đã xác minh HMAC, mismatch/duplicate và pessimistic lock; public HTTPS IPN E2E vẫn chưa có bằng chứng. |
| Refund | Chưa có | Chưa có | `RefundCommand` rỗng và `PaymentService.refund()` chưa có logic. |
| Idempotency/concurrency | Một phần | Chưa có | Có request hash, TTL, PostgreSQL advisory locks, active-payment constraint và pessimistic IPN lock; concurrency test PostgreSQL chưa chạy. |
| Bảo mật | Một phần | Chưa có | VNPay signature và secret externalization đã có; internal API vẫn chưa có authentication/authorization và secret-manager integration. |
| Redis/RabbitMQ | Chưa có | Chưa có | Dependency và cấu hình đã tồn tại nhưng chưa có producer, consumer, cache hoặc outbox. |
| Xử lý lỗi | Một phần | Chưa có | API thường có `ControllerAdvice` và error envelope; provider contracts ngoài VNPay chưa hoàn thiện. |
| Observability | Chưa có | Chưa có | Chưa thấy health/metrics nghiệp vụ, tracing, structured logging hoặc audit log. |
| Testing | Một phần | Chưa có | Domain/service/VNPay/MVC có 63 test pass; bốn migration/context/concurrency Testcontainers test, E2E sandbox và coverage gate còn thiếu. |
| Build/deployment | Một phần | Chưa có | Có Maven Wrapper và verify workflow; chưa có Dockerfile, deployment template hoặc CI run được bảo vệ. |

## Khoảng trống và rủi ro chính

VNPay hiện đã code-complete ở mức deterministic checkout/IPN/QueryDR. Checkout sandbox đã được thao tác, nhưng chưa có public HTTPS IPN và bằng chứng database chuyển trạng thái từ callback thật. `PRV-04` vì vậy vẫn chưa đạt.

Transaction/idempotency persistence và các lock đã được triển khai, nhưng chưa thể chạy PostgreSQL integration/concurrency tests vì Docker Desktop chưa hoạt động. Đây là blocker cho việc công nhận migration, rollback và race-condition behavior.

API public query/refund, authentication/authorization, webhook-event audit, outbox và observability vẫn chưa có. QueryDR nội bộ chỉ phục vụ reconciliation VNPay; MoMo/bank vẫn là skeleton và bị disable trước khi xử lý nghiệp vụ.

## Roadmap đề xuất

### P0 — Hoàn thiện tính đúng đắn và an toàn

1. Khởi động Docker Desktop và chạy `./mvnw clean verify` để nghiệm thu Flyway, constraints và context.
2. Chạy integration/concurrency assertions đã bổ sung cho simultaneous create/IPN và rollback.
3. Cấu hình Cloudflare public HTTPS IPN và chạy sanitized E2E smoke test.
4. Thêm authentication/authorization cho payment API và bảo vệ operational endpoints.
5. Đưa CI workflow thành required branch check và lưu test/security reports.

### P1 — Hoàn thiện nghiệp vụ MVP mở rộng

1. Triển khai refund toàn phần/một phần và lưu refund transaction.
2. Thêm API truy vấn payment, transaction và trạng thái theo order.
3. Mở rộng reconciliation sang audit/metrics và quy trình xử lý thủ công sau khi QueryDR exhausted.
4. Đưa RabbitMQ/Redis vào luồng có mục đích rõ ràng; ưu tiên transactional outbox nếu phát sự kiện thanh toán.
5. Bổ sung migration roll-forward/rollback runbook và integration test nâng cấp schema.

### P2 — Sẵn sàng vận hành production

1. Thêm metrics, tracing, structured logging, audit trail và cảnh báo theo tỷ lệ lỗi/độ trễ.
2. Áp dụng timeout, retry/backoff, circuit breaker, rate limit và quy trình xử lý dead-letter.
3. Thiết lập CI/CD, Docker image, quét dependency/secret, coverage gate và môi trường staging.
4. Xuất bản OpenAPI, runbook vận hành; thực hiện performance, failure-recovery và security testing.

## Trạng thái xác minh

Source chính compile thành công bằng JDK 17.0.12. Bộ chọn lọc 63 unit/MVC test chạy pass, không có failure/skip. Bốn test Spring context/Flyway/concurrent IPN chưa chạy vì Docker Desktop engine không hoạt động, nên chưa công nhận `clean verify`, migration V2 hoặc các tiêu chí concurrency/CI.
