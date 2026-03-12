# Spring Boot Reference

A reference project codifying best practices for Java/Spring Boot applications — project structure, component design, testing patterns, and more. Skills are added incrementally as patterns are established.

## Philosophy

This project codifies patterns and conventions as both **working code** and **reusable skills**. Every convention is backed by a concrete implementation in the codebase.

### Production patterns
- **Domain-oriented packages** — packages named after domain concepts, not technologies; 5–8 file threshold triggers evaluation
- **Component design** — controllers are thin, services orchestrate, repositories query, clients wrap external boundaries
- **Naming conventions** — `*Service` for orchestrators, `*Client` for external boundaries, descriptive names for standalone logic
- **Entity design** — `with*` fluent mutators, pure computation returning result records, no public setters

### Testing patterns
- **Contract testing over implementation testing** — assert on observable side effects, not internal method calls
- **Real infrastructure** — Postgres via Docker, no in-memory substitutes
- **Test doubles, not mocks** — in-memory implementations that record calls and validate inputs
- **Test data isolation** — random IDs, fresh data per test, contextual domain in setup

## Project Structure

```
src/main/java/com/alleato/ecommerce/ordering/
├── controller/         OrderController (REST API — thin, delegates to service)
├── service/            OrderService (orchestrator — coordinates components)
├── models/             Order, OrderLineItem, DTOs, enums
├── repository/         OrderRepository (Spring Data JPA)
├── pricing/            PricingCalculator + PricingResult (pure computation)
├── invoicing/          Invoice generation + document storage
│   ├── InvoiceService.java             (orchestrates generation + storage)
│   ├── DocumentClient.java             (interface — S3)
│   └── S3DocumentClient.java           (uses S3Client)
├── payment/            Payment processing
│   ├── PaymentClient.java              (interface — Stripe)
│   └── StripePaymentClient.java        (@Profile("!test") — no real Stripe SDK)
├── notification/       Order notifications
│   ├── NotificationClient.java         (interface — SNS)
│   └── SnsNotificationClient.java      (uses SnsClient)
├── fulfillment/        Order fulfillment
│   ├── FulfillmentClient.java          (interface — SQS)
│   ├── FulfillmentPayload.java         (payload record)
│   └── SqsFulfillmentClient.java       (uses SqsClient)
└── inventory/          Inventory management
    ├── InventoryClient.java            (interface — external API)
    └── HttpInventoryClient.java        (@Profile("!test") — uses RestClient)

src/test/java/com/alleato/ecommerce/ordering/
├── support/            Shared test infrastructure
│   ├── BaseIntegrationTest.java        (test wiring + automatic reset)
│   ├── TestConfiguration.java          (Spring @TestConfiguration)
│   ├── clients/        Test clients and doubles
│   │   ├── OrderClient.java            (typed REST client for test use)
│   │   ├── TestPaymentClient.java      (test PaymentClient — configurable success/failure)
│   │   └── TestInventoryClient.java    (test InventoryClient — in-memory, configurable stock)
│   └── aws/clients/    AWS SDK test doubles
│       ├── TestSqsClient.java          (test SqsClient — validates queues, records calls)
│       ├── TestSnsClient.java          (test SnsClient — validates topics, records calls)
│       ├── TestS3Client.java           (test S3Client — in-memory storage, validates buckets)
│       └── simulators/  Simulator pattern (for SDKs without interfaces)
│           ├── ExpectedException.java  (configurable exception rules)
│           ├── SqsSimulator.java       (stateless — fire-and-forget messages)
│           ├── SnsSimulator.java       (stateless — fire-and-forget publications)
│           └── S3Simulator.java        (stateful — in-memory object store)
├── controller/
│   └── OrderApiIntegrationTest.java    (integration test)
├── repository/
│   └── FlywayMigrationIntegrationTest.java
└── pricing/
    └── PricingCalculatorTest.java      (unit test)
```

## Key Patterns

### Production

#### Component responsibilities

| Component | Responsibility | Key rules |
|---|---|---|
| **Controller** | Receive HTTP requests, return responses | Thin — no business logic, delegates to one service, returns response DTOs |
| **Service** | Orchestrate business workflows | One public method per use case, owns `@Transactional`, constructor injection |
| **Repository** | Data access | Spring Data conventions, `@Query` for joins, no business logic |
| **Client** | Wrap external service boundaries | Interface is the contract, one method per operation, result records with static factories |
| **Pub/Sub** | Asynchronous message contracts | Payload records in the producing subdomain, minimal payloads, idempotency keys |

See `component-design` skill for method sizing, composition, overloading, and overriding guidelines.

#### Naming conventions

| Suffix | When to use | Example |
|---|---|---|
| `*Service` | Orchestrates other components (depends on clients, repositories, etc.) | `OrderService`, `InvoiceService` |
| `*Client` | Interface or implementation that wraps an external service | `PaymentClient`, `StripePaymentClient` |
| `*Calculator`, `*Engine`, etc. | Standalone logic with no dependencies — pure computation | `PricingCalculator` |

`*Service` implies dependencies. Standalone logic should use a descriptive name — not `*Service`. External service implementations are prefixed with what they integrate: `Stripe*`, `Sns*`, `S3*`, `Sqs*`, `Http*`.

See `naming-conventions` skill for the full rules including test naming and method naming.

#### Entity design

JPA entities use `with*` fluent mutators instead of public setters. Pure computation returns result records rather than mutating entities:

```java
PricingResult pricing = pricingCalculator.calculate(order.getLineItems(), promoCode);
order.withSubtotal(pricing.subtotal())
     .withDiscount(pricing.discount())
     .withTotal(pricing.total());
```

See `entity-design` skill for the full pattern.

#### Package constraints

- Packages named after domain concepts, not technologies (`payment/` not `stripe/`)
- **5–8 files per package** — when a package exceeds this, evaluate whether it represents multiple domains that should be split
- Core domain uses layered packages; supporting subdomains are flat

See `project-structure` skill for the decision framework.

### Testing

#### Contract testing

Integration tests assert on **observable outcomes** — not internal method calls:
- HTTP response status and body
- Database state (order persisted with correct status)
- SDK call recordings (payment charged, notification published, fulfillment enqueued, inventory reserved, invoice stored)

#### Test doubles

| Pattern | When | Example |
|---|---|---|
| SDK-level | Service has a public SDK (AWS) | `TestSqsClient` implements `SqsClient` |
| Interface-level | No SDK, custom client interface | `TestPaymentClient` implements `PaymentClient` |

All test doubles support `throwWhen` for failure simulation and `reset()` for cleanup between tests.

#### Test data isolation

Every test creates its own data with random IDs. Contextual domain (e.g., customer) is initialized in `@BeforeEach`. Tests never depend on data from other tests.

#### Real Postgres via Docker

Tests connect to a real Postgres instance — Flyway migrations run against real Postgres, no H2/HSQLDB substitution.

## Prerequisites

- **Java 25+** (GraalVM CE recommended — install via SDKMAN: `sdk install java 25.0.2-graalce`)
- **Docker** (for Postgres)

## Running Tests

```bash
docker-compose up -d    # Start Postgres
./gradlew test          # Run all tests
docker-compose down     # Stop Postgres when done
```

## Domain

A simplified order management system:
1. Customer submits an order via REST API
2. Pricing is calculated (subtotals, promo codes, bulk discounts)
3. Order is persisted to Postgres
4. Payment is charged via payment client (Stripe in prod)
5. Inventory is reserved via inventory client (external REST API in prod)
6. Invoice is generated and stored via document client (S3 in prod)
7. Order confirmation notification is published (SNS in prod)
8. Fulfillment job is enqueued (SQS in prod)

## Documentation

Detailed documentation lives in [`docs/`](docs/):

| Document | Description |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, component design, domain workflows |
| [TESTING.md](docs/TESTING.md) | Testing strategy, infrastructure, conventions |
| [DATABASE.md](docs/DATABASE.md) | Schema, data models, relationships |
| [MIGRATIONS.md](docs/MIGRATIONS.md) | Flyway migration strategy |
| [RELEASE.md](docs/RELEASE.md) | Release process |
| [SECURITY.md](docs/SECURITY.md) | Application security design |
| [HOW_TO.md](docs/HOW_TO.md) | Setup, configuration, gotchas, troubleshooting |

Feature documentation in [`docs/feature/`](docs/feature/):

| Feature | Description |
|---|---|
| [ORDER_CREATION.md](docs/feature/ORDER_CREATION.md) | Order creation workflow — the primary feature |
| [ORDER_RETRIEVAL.md](docs/feature/ORDER_RETRIEVAL.md) | Order retrieval by ID |

## Agent Skills

This project includes reusable [Claude Code skills](https://docs.anthropic.com/en/docs/claude-code/skills) in `.claude/skills/` that codify the patterns demonstrated here. Skills are Java/Spring Boot specific — for other languages, create a separate reference project with language-appropriate skills.

### Available skills

#### Production

| Skill | Description |
|---|---|
| `project-structure` | Domain-oriented package layout. Core domain uses layered packages, supporting subdomains are flat. 5–8 file threshold for package evaluation. |
| `component-design` | Design rules for controllers, services, repositories, clients, and pub/sub — file responsibility, method sizing, composition, overloading, and overriding. |
| `naming-conventions` | Class suffix rules — `*Service` for orchestrators, `*Client` for external boundaries, descriptive names for standalone logic. |
| `entity-design` | JPA entities with `with*` fluent mutators, pure computation returning result records, no public setters. |
| `dependency-injection` | Constructor injection only, no inline dependency construction, `@Bean` methods own object creation. |
| `error-handling` | Single project exception (`OrderingException`) with factory methods, centralized `@ControllerAdvice`, context maps instead of exception hierarchies. |
| `concurrency` | Virtual threads, structured concurrency for fan-out, CompletableFuture composition, connection pool protection, no `@Async`. |

#### Project

| Skill | Description |
|---|---|
| `project-documentation` | Required documentation structure — root files (README, CONTRIBUTING, LICENSE, SECURITY), docs/ directory (architecture, testing, database, migrations, release, security design, how-to, feature docs). |

#### Testing

| Skill | Description |
|---|---|
| `adding-integration-tests` | Adds integration tests for Spring Boot API endpoints. Tests boot the full app with Postgres, exercise the API via HTTP, and assert on observable side effects. |
| `adding-unit-tests` | Adds unit tests for pure business logic with no Spring context. |
| `test-data-isolation` | Ensures tests are independent via random IDs, fresh data per test, and contextual domain setup in `@BeforeEach`. |
| `adding-flyway-migrations` | Adds a Flyway migration and updates the schema test. |
| `testing-boundaries` | Test doubles for external dependencies: SDK interfaces, SDK simulators, custom REST API interfaces. |
| `setting-up-docker-for-tests` | Sets up Docker-managed Postgres for integration tests. Includes a decision framework for what to dockerize vs use test doubles. |

### Adopting in your project

1. Copy the `.claude/skills/` directory into your project:
   ```bash
   cp -r .claude/skills/ /path/to/your-project/.claude/skills/
   ```
2. Adapt the templates to your project's package names, domain models, and infrastructure.
3. Skills are automatically available to Claude Code when working in your project — no additional configuration needed.
