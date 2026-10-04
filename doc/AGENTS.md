# Repository Guidelines

## Project Structure & Module Organization

This is a Java 17 Spring Boot payment service built with Maven. Production code lives under `src/main/java/com/thanh/payment` and follows a hexagonal structure:

- `domain/`: payment and transaction models, value objects, and repository contracts.
- `application/`: use-case ports, commands, DTOs, mappers, and orchestration services.
- `adapter/out/provider/`: integrations for bank, MoMo, and VNPay providers.
- `infrastructure/persistence/`: Spring Data JPA repositories and persistence adapters.
- `presentation/`: REST controllers and request DTOs.

Tests mirror the production package tree in `src/test/java`. Runtime configuration is in `src/main/resources/application.yaml`; database DDL is in `src/main/resources/db/schema-payments.sql`.

## Build, Test, and Development Commands

Use the checked-in Maven wrapper so builds use a consistent Maven version.

- `./mvnw spring-boot:run` starts the service with the local PostgreSQL, Redis, and RabbitMQ settings.
- `./mvnw test` runs JUnit 5 unit and Spring context tests. Docker must be running for Testcontainers-based PostgreSQL and Redis tests.
- `./mvnw clean package` runs tests and creates the executable JAR under `target/`.
- `./mvnw -DskipTests package` builds quickly without verification; use only for local iteration.

On Windows, replace `./mvnw` with `mvnw.cmd`.

## Coding Style & Naming Conventions

Use four-space indentation for Java and two spaces for YAML. Follow standard Java naming: `PascalCase` types, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` enum constants. Keep domain logic free of Spring and provider concerns. Name ports by capability (`CreatePaymentUseCase`) and implementations/adapters by role (`PaymentRepositoryImpl`, `VnpayAdapter`). Lombok is enabled; use it consistently without hiding important domain behavior. No formatter or linter is configured, so format changed files with the IDE before committing.

## Testing Guidelines

Write JUnit 5 tests beside the corresponding package and suffix classes with `Test`; use behavior-focused methods such as `shouldRefundCompletedPayment`. Cover state transitions, validation failures, and provider boundary behavior. Use plain unit tests for domain logic and reserve `@SpringBootTest` plus Testcontainers for wiring or persistence integration.

## Commit & Pull Request Guidelines

History follows Conventional Commits, usually scoped: `feat(webhook): implement verification endpoints` or `chore(config): configure Redis`. Keep commits focused and imperative. Pull requests should explain behavior changes, list verification commands, link relevant issues, and include sample requests/responses for API changes. Call out schema or configuration changes explicitly.

## Security & Configuration

Never commit real provider secrets or production credentials. Override local defaults through environment-specific configuration, and redact signatures, payment tokens, and customer data from logs and review artifacts.
