# Testing Strategy

## Overview

Tests assert on **observable side effects** — HTTP responses, database state, and SDK call recordings. Never assert on internal implementation details.

## Test types

| Type | Suffix | Spring context | When to use |
|---|---|---|---|
| **Unit tests** | `*Test` | No | Pure business logic (calculators, parsers, validators) |
| **Integration tests** | `*IntegrationTest` | Full app + Postgres | API endpoints, workflows, external service interactions |

## Running tests

```bash
docker-compose up -d    # Start Postgres (required for integration tests)
./gradlew test          # Run all tests
docker-compose down     # Stop Postgres when done
```

## Test infrastructure

### Database

Real Postgres via Docker (`docker-compose.yml`). Tests connect to `orders_test` database on port 15432. Flyway migrations run automatically on test startup.

No H2 or HSQLDB — tests run against the same database engine as production.

### Test doubles

| External service | Test double | Type |
|---|---|---|
| SQS | `TestSqsClient` | SDK-level (implements `SqsClient`) |
| SNS | `TestSnsClient` | SDK-level (implements `SnsClient`) |
| S3 | `TestS3Client` | SDK-level (implements `S3Client`) |
| Stripe | `TestPaymentClient` | Interface-level (implements `PaymentClient`) |
| Inventory API | `TestInventoryClient` | Interface-level (implements `InventoryClient`) |

All test doubles:
- **Record calls** for assertion (message bodies, amounts, keys)
- **Support `throwWhen`** for failure simulation
- **Support `reset()`** for cleanup between tests
- **Validate resources** (queue URLs, topic ARNs, bucket names)

### Simulator pattern

For SDK clients without interfaces, simulators in `support/aws/clients/simulators/` provide:
- Mockito-based mock clients
- Direct request list tracking (full SDK metadata preserved)
- `ExpectedException<T>` for configurable failure rules
- Pattern matching for querying by operation type

### BaseIntegrationTest

All integration tests extend `BaseIntegrationTest`, which provides:
- Injected test doubles (`testSqsClient`, `testSnsClient`, `testS3Client`, `testPaymentClient`, `testInventoryClient`)
- Injected `OrderClient` for typed HTTP calls
- Automatic `reset()` of all test doubles in `@BeforeEach`

### TestConfiguration

Spring `@TestConfiguration` that wires all test doubles as `@Bean` methods. Real SDK implementations are replaced by test doubles via dependency injection.

## Test conventions

### Naming
- Test class name matches the class under test: `PricingCalculatorTest`, `OrderApiIntegrationTest`
- Test methods describe behavior: `creates_order_and_returns_confirmed_status`
- Use `@Nested` classes with `@DisplayName` to group scenarios

### Location
- Tests live in the **same package** as the class they test
- Test support classes live in `support/`

### Assertions
- Use **AssertJ** for all assertions
- Assert on observable outcomes: HTTP response, DB state, SDK recordings
- Deserialize SQS/SNS message bodies to verify payload content

### Test data isolation
- Random IDs per test (customer IDs, product IDs)
- Fresh data in `@BeforeEach` — no cross-test dependencies
- Contextual domain setup (customer, products) initialized per test class

### Failure testing
- Use `throwWhen` on test doubles to simulate external service failures
- Verify that downstream side effects do NOT happen when upstream steps fail
- Example: payment failure should NOT trigger notification, invoice, inventory, or fulfillment

## File locations

| File | Purpose |
|---|---|
| `support/BaseIntegrationTest.java` | Base class for integration tests |
| `support/TestConfiguration.java` | Test bean wiring |
| `support/clients/OrderClient.java` | Typed HTTP client for API calls |
| `support/clients/TestPaymentClient.java` | Payment test double |
| `support/clients/TestInventoryClient.java` | Inventory test double |
| `support/aws/clients/TestSqsClient.java` | SQS test double |
| `support/aws/clients/TestSnsClient.java` | SNS test double |
| `support/aws/clients/TestS3Client.java` | S3 test double |
| `support/aws/clients/simulators/` | Simulator pattern for no-interface SDKs |
