# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A self-built payment gateway module (Java 17, Spring Boot 4.1, Maven, PostgreSQL, Flyway, JPA/Hibernate 7, Lombok). The goal is a hosted-checkout/QR gateway for VNPay, MoMo and bank transfer; the service never handles PAN/CVV. **VNPay** is fully implemented (create, IPN, QueryDR); **payOS** can create payment links only (`PayosAdapter`, webhook and reconciliation pending, see `doc/PAYOS_INTEGRATION.md`); MoMo and bank adapters are disabled skeletons. Redis is used only for idempotency keys (`RedisIdempotencyStore`); RabbitMQ is configured but not used by any code yet.

Project docs are in Vietnamese under `doc/`:
- `doc/PRODUCTION_READINESS_ROADMAP.md` — the source of truth for progress: 121 criteria (ARCH, DAT, API, PRV, WEB, REF, EVT, SEC, REL, OBS, TST, OPS), release gates P0/P1/P2, and the milestone path (M0–M6).
- `doc/PROJECT_STATUS.md` — narrative status snapshot.
- `doc/VNPAY_SANDBOX_RUNBOOK.md` — Cloudflare Tunnel + sandbox IPN/QueryDR procedure.
- `doc/AGENTS.md` — coding/commit conventions (summarized below).

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows, the primary dev machine).

```bash
./mvnw clean verify                      # what CI runs; needs Docker for Testcontainers
./mvnw test -Dtest=PaymentServiceTest    # one test class
./mvnw test -Dtest='PaymentServiceTest#shouldRejectUnsupportedMethodBeforePersistence'   # one method
./mvnw spring-boot:run                   # runs with profile local (set in the plugin config); reads .env; PostgreSQL localhost:5432/payment
npx @redocly/cli@2.53.3 lint src/main/resources/static/openapi/payment-api.yaml   # OpenAPI lint (CI step)
git diff --check                         # CI whitespace check
```

Testcontainers tests (`PaymentApplicationTests`, `FlywayMigrationIntegrationTest`, `VnpayIpnPersistenceIntegrationTest`) start `postgres:17.6-alpine` and `redis:8.2.1-alpine`; they use `@ActiveProfiles("test")` with VNPay test credentials in `src/test/resources/application-test.yaml`. Everything else is plain unit or `@WebMvcTest` and runs without Docker.

CI (`.github/workflows/verify.yml`): JDK 17 Temurin → Redocly lint → `./mvnw clean verify` → `git diff --check`, plus a gitleaks secret scan job.

## Architecture

Single bounded context, layered like the job-backend project (controller → service → Spring Data repository → entity, no inbound ports, no repository adapters), in `src/main/java/com/thanh/payment`:

- `api/` — `PaymentController`, `WebhookController`, `GlobalExceptionHandler` (error envelope `code/message/traceId/timestamp/fieldErrors`). `api/dto` holds request/response DTOs, `api/dto/webhook` the provider callback payloads.
- `application/` — concrete `@Service` classes injected directly by controllers (`PaymentService`, `PaymentCreator`, `IdempotencyGuard`, `VnpayIpnService`, `VnpayIpnProcessor`, `VnpayReconciliation*`) and the provider factories. `application/command` holds commands; `application/provider` holds the real strategy interfaces (`PaymentProvider`, `WebhookVerifier`, one implementation per provider) with their request/result types.
- `domain/` — `Payment` (aggregate root) and `Transaction` own the state machines (`markCompleted`, `markFailed`, `markReviewRequired`, `markExpired`, refund transitions). `domain/enums` holds domain status and type enums (`PaymentStatus`, `PaymentMethodType`, `TransactionStatus`, `TransactionType`, `IdempotencyState`). `Money` normalizes amount/currency. `domain/repository` holds the Spring Data `JpaRepository` interfaces directly (no Impl/adapter layer). Entities are JPA-annotated and use TSID ids (`@Tsid`, hypersistence-utils), so ids exist before the DB insert returns.
- `mapper/` — `PaymentMapper`. `shared/exception/` — `ApiException` carries HTTP status + error code + optional `Retry-After`.
- `infrastructure/provider/{vnpay,payos,momo,bank}/` — one package per provider: adapter, signature service, IPN verifier / QueryDR client and codec, and its `*Properties` (`VnpayProperties` is validated configuration bound from `payment.providers.vnpay.*`).
- `infrastructure/` also has `cache/` (`RedisIdempotencyStore`, atomic Lua scripts), `persistence/` (`SequenceProviderOrderCodeGenerator`), `job/` (`VnpayReconciliationScheduler`) and `config/` (scheduling, `Clock` bean).

### Payment flow (VNPay)

1. `POST /api/v1/payments` with `Idempotency-Key` → `PaymentService.createPayment`: validates (VNPAY + VND only, whole amount ≥ 5000), `IdempotencyGuard` atomically reserves the key in Redis (`IN_PROGRESS` 30s → `COMPLETED` 24h; same key+request in flight → 409 `IDEMPOTENCY_IN_PROGRESS`, different request → 409 `IDEMPOTENCY_CONFLICT`, Redis down → 503), then `PaymentCreator` (separate bean = one DB transaction with a timeout, `payment.idempotency.max-processing-time-seconds`) INSERTs `idempotency_keys` **first** (so a retry hits the key constraint, not the order constraint), then `Payment` + charge `Transaction`, builds the signed checkout URL and marks the key `COMPLETED`. Duplicates are caught by DB unique constraints, translated in `PaymentCreator` to `DuplicateIdempotencyKeyException` (→ replay) and `ActivePaymentExistsException` (→ 409 `PAYMENT_ALREADY_PENDING`), not by SELECT-then-INSERT. `createPayment` itself is not `@Transactional`. **`vnp_TxnRef` is `Transaction.id`, not `orderId`.**
2. Browser goes to VNPay. `GET /webhook/vnpay/return` is UX only and never changes state.
3. `GET /api/v1/payments/webhook/vnpay` (IPN) → `VnpayIpnService` verifies the signature **before any DB access**, checks TmnCode, then `VnpayIpnProcessor` locks the transaction row (`findByIdForUpdate`), checks amount ×100 / method / currency, and maps codes. Only `vnp_ResponseCode=00` **and** `vnp_TransactionStatus=00` is success; `07` → review required; `11` → expired; known decline codes → failed; anything else → `RspCode=99` with no state change. Always HTTP 200 with VNPay's `RspCode` (`00/01/02/04/97/99`).
4. Missed IPN: `VnpayReconciliationScheduler` (enabled by `VNPAY_RECONCILIATION_ENABLED`; default on in staging/production, which then require `VNPAY_QUERY_API_URL` and `VNPAY_MERCHANT_IP`) runs QueryDR for `PENDING` charges past `expired_at`, every 5 min, max 12 attempts; when exhausted the payment stays `PENDING` with `reconciliation_exhausted=true` for manual handling. A signed QueryDR response `91` (no such transaction) after `expired_at` + `reconciliation-not-found-grace` turns the payment `EXPIRED` so an abandoned checkout stops locking the order. It never overwrites a terminal transaction. An IPN success for an already `EXPIRED`/`FAILED` charge returns `02` and logs ERROR for manual refund.

MoMo/bank go through the older generic path `PaymentService.verifyWebhook` → `processTransaction`, which does not check amounts. It is unreachable while those providers are disabled; replace it with a dedicated processor (like `VnpayIpnProcessor`) rather than extending it.

## Schema and configuration rules

- Flyway owns the schema (`spring-boot-starter-flyway` is required on Boot 4; plain `flyway-core` is silently ignored). Hibernate runs with `ddl-auto: validate` in every profile, so any new entity field needs a migration or the context fails to start.
- There is no default profile in `application.yaml`. `spring-boot:run` uses `local` via the Maven plugin config; the JAR must get `SPRING_PROFILES_ACTIVE`. Only `application-local.yaml` imports `.env`.

## Known problems

- `PaymentCreator` calls the provider inside the DB transaction. Fine for VNPay (local URL signing). `PayosAdapter` makes a remote HTTP call there (timeouts 2s+5s, below the 10s transaction timeout), holding the order's active-payment row meanwhile; move the call out (commit PENDING, call, update) before real traffic or before adding MoMo.
- payOS `orderCode` comes from `provider_order_code_seq` (V5) because TSID ids exceed payOS's 2^53-1 limit; never reset that sequence, payOS rejects reused codes.

## Conventions

- Configuration: profiles `local`, `test`, `staging`, `production`; secrets only from environment variables (`VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `SPRING_DATASOURCE_*`, ...). `.env` is gitignored and must never be committed or read into logs. Never log signatures, hash secrets or full checkout URLs.
- Schema changes go in a new `src/main/resources/db/migration/V{n}__*.sql`; never edit an applied migration.
- Keep provider-specific code (signing, HTTP, protocol codes) in `infrastructure/provider/<name>` and business rules in `domain` and `application`. Services are concrete classes (no `XxxUseCase` or `XxxServiceImpl`); add an interface only when there are several real implementations, like `PaymentProvider`. Provider adapters are named by role (`VnpayAdapter`).
- Tests mirror the main package tree, class suffix `Test`, behavior-style method names. Use plain unit tests for domain logic and `@SpringBootTest` + Testcontainers only for wiring/persistence. Pass a fixed `Clock` for anything time-dependent.
- Commits use scoped Conventional Commits, e.g. `feat(webhook): ...`, `chore(config): ...`.
- Roadmap rules: tick `[x]` only with linkable evidence (code, test, CI run, runbook); update the group count, dashboard, release gates and evaluation date in the same change. Partial work goes under "Tiến độ hiện tại" and does not count.
