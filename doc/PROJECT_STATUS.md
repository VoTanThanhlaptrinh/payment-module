# Báo cáo tiến độ Payment Module

> **Thời điểm đánh giá:** 04/10/2026  
> **Phạm vi:** Snapshot hiện tại của repository, đối chiếu ở hai mức MVP backend và production-ready.

## Cách đọc báo cáo

- **Đã có:** Năng lực cốt lõi đã được triển khai trong code.
- **Một phần:** Đã có mô hình hoặc luồng chính nhưng còn giả lập, thiếu kết nối, persistence hoặc kiểm thử cần thiết.
- **Chưa có:** Chưa thấy triển khai trong repository.

Dự án sử dụng Java 17, Spring Boot, Maven, PostgreSQL, Redis, RabbitMQ, JPA, Lombok, JUnit 5 và Testcontainers. Cấu trúc package đi theo hướng hexagonal với các lớp `domain`, `application`, `adapter`, `infrastructure` và `presentation`, phù hợp với hướng dẫn trong `AGENTS.md`.

## Kết quả đã đạt được

- Đã xây dựng domain `Payment`, `Transaction`, value object `Money`, tiền tệ, phương thức thanh toán và các trạng thái nghiệp vụ. Các quy tắc hoàn tất, thất bại, hoàn tiền và hết hạn được đóng gói trong entity.
- Đã có persistence cho `Payment` qua Spring Data JPA. Schema PostgreSQL có index tra cứu `order_id` và partial unique index nhằm giới hạn một payment `PENDING` cho mỗi đơn hàng.
- Đã có API `POST /api/v1/payments`, validation request và mapping kết quả trả về.
- Đã định nghĩa `PaymentProvider`, factory chọn provider và adapter cho VNPay, MoMo, chuyển khoản ngân hàng.
- Đã có ba webhook: `GET /api/v1/payments/webhook/vnpay`, `POST /momo` và `POST /bank`. Webhook có thể ánh xạ kết quả sang trạng thái `COMPLETED` hoặc `FAILED` của payment đang chờ.
- Có 22 test method: 21 test domain cho `Payment`, `Transaction`, `Money` và một Spring context test sử dụng Testcontainers.
- Lịch sử Git dùng Conventional Commits có scope, thể hiện các mốc domain, kiến trúc, database, provider và webhook tương đối rõ ràng.

## Ma trận mức độ hoàn thiện

| Năng lực | MVP backend | Production-ready | Hiện trạng |
| --- | --- | --- | --- |
| Domain payment/transaction | Đã có | Một phần | Có entity, value object và state transition; domain vẫn phụ thuộc annotation JPA. |
| API thanh toán | Một phần | Chưa có | Mới có tạo payment; chưa có API lấy trạng thái, lịch sử, hủy hoặc refund. |
| Database | Một phần | Chưa có | Có bảng `payments`; chưa có schema/repository cho `transactions` và chưa dùng migration tool. |
| Tích hợp provider | Một phần | Chưa có | Có interface, factory và adapter nhưng `processPayment()` đều chưa gọi API thật. |
| Webhook/IPN | Một phần | Chưa có | Có endpoint và cập nhật trạng thái; xác minh chữ ký hiện chỉ là kiểm tra giả lập. |
| Refund | Chưa có | Chưa có | `RefundCommand` rỗng và `PaymentService.refund()` chưa có logic. |
| Idempotency/concurrency | Một phần | Chưa có | Có kiểm tra payment `PENDING` và unique index; chưa có idempotency key, event deduplication hoặc xử lý race condition đầy đủ. |
| Bảo mật | Chưa có | Chưa có | Chưa có xác thực API/webhook thật; credential PostgreSQL đang ghi trực tiếp trong YAML. |
| Redis/RabbitMQ | Chưa có | Chưa có | Dependency và cấu hình đã tồn tại nhưng chưa có producer, consumer, cache hoặc outbox. |
| Xử lý lỗi | Một phần | Chưa có | Có exception tại domain/service nhưng chưa có `ControllerAdvice` và error response thống nhất. |
| Observability | Chưa có | Chưa có | Chưa thấy health/metrics nghiệp vụ, tracing, structured logging hoặc audit log. |
| Testing | Một phần | Chưa có | Domain được test khá tốt; thiếu test service, controller, repository, provider, webhook và coverage gate. |
| Build/deployment | Một phần | Chưa có | Có Maven Wrapper; chưa có Dockerfile, Compose, pipeline CI/CD hoặc cấu hình triển khai. |

## Khoảng trống và rủi ro chính

Project hiện là nền tảng MVP có cấu trúc tốt, nhưng chưa phải luồng thanh toán end-to-end: tạo payment chỉ gọi các `processPayment()` rỗng nên chưa sinh URL/QR/token thanh toán từ provider. Chữ ký webhook chưa dùng secret và thuật toán HMAC thực tế; payload cũng chưa được đối chiếu số tiền, tiền tệ hoặc mã giao dịch với dữ liệu đã lưu.

`Transaction` đã có domain model nhưng chưa được lưu. `transactionId` nhận từ webhook được đưa vào `ProcessTransactionCommand` rồi không sử dụng, làm thiếu dấu vết đối soát và chống callback trùng. Các phương thức `CREDIT_CARD` và `COD` có trong enum nhưng không có provider; yêu cầu tạo payment bằng hai phương thức này sẽ bị factory từ chối.

Ngoài ra, `application.port.out.PaymentRepository` đang rỗng và trùng vai trò với `domain.repository.PaymentRepository`. Việc dùng `spring.jpa.hibernate.ddl-auto: update`, image Testcontainers gắn tag `latest`, và credential trong `application.yaml` chưa phù hợp cho môi trường production.

## Roadmap đề xuất

### P0 — Hoàn thiện tính đúng đắn và an toàn

1. Ngoại hóa toàn bộ credential/secret; tách profile local, test và production.
2. Triển khai API thật cho provider, trả payment URL/QR/token và xác minh HMAC đúng đặc tả từng cổng.
3. Bổ sung schema, repository và lifecycle cho `Transaction`; lưu provider transaction ID, lỗi và thời điểm hoàn tất.
4. Hoàn thiện idempotency cho create payment và webhook, kiểm tra amount/currency/order, xử lý callback lặp và race condition.
5. Chuẩn hóa exception/error response; bổ sung test service, provider, controller, repository và webhook.

### P1 — Hoàn thiện nghiệp vụ MVP mở rộng

1. Triển khai refund toàn phần/một phần và lưu refund transaction.
2. Thêm API truy vấn payment, transaction và trạng thái theo order.
3. Bổ sung cơ chế reconciliation với provider, timeout payment và retry có kiểm soát.
4. Đưa RabbitMQ/Redis vào luồng có mục đích rõ ràng; ưu tiên transactional outbox nếu phát sự kiện thanh toán.
5. Chuyển quản lý schema sang Flyway hoặc Liquibase và bổ sung integration test đầy đủ.

### P2 — Sẵn sàng vận hành production

1. Thêm metrics, tracing, structured logging, audit trail và cảnh báo theo tỷ lệ lỗi/độ trễ.
2. Áp dụng timeout, retry/backoff, circuit breaker, rate limit và quy trình xử lý dead-letter.
3. Thiết lập CI/CD, Docker image, quét dependency/secret, coverage gate và môi trường staging.
4. Xuất bản OpenAPI, runbook vận hành; thực hiện performance, failure-recovery và security testing.

## Trạng thái xác minh

Các nhận định trên được đối chiếu với source code, `pom.xml`, `application.yaml`, schema SQL, test và Git history. Lệnh `./mvnw test` chưa thể được xác minh trong môi trường đánh giá vì không có Java (`java: command not found`); đây là giới hạn của môi trường hiện tại, không phải kết luận rằng test của project bị lỗi.
