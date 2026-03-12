# CLAUDE.md

## Project Overview

Spring Boot reference project codifying best practices for Java/Spring Boot applications — project structure, component design, testing patterns, and more. Skills are added incrementally as patterns are established.

## Build & Test

```bash
docker-compose up -d    # Start Postgres (required for integration tests)
./gradlew test          # Run all tests
docker-compose down     # Stop Postgres when done
```

## Architecture

- **Domain**: Order management (create orders, process payments, reserve inventory, generate invoices, publish notifications, enqueue fulfillment)
- **Package structure**: Domain-oriented — core domain uses layered packages (controller/service/models/repository), supporting subdomains are flat packages. 5–8 files per package; exceeding triggers evaluation.
- **Database**: Postgres with Flyway migrations (`src/main/resources/db/migration/`)
- **External services**: Behind interfaces in domain packages (`payment/`, `fulfillment/`, `inventory/`, `notification/`, `invoicing/`)

## Component Design

- **Controllers**: Thin — no business logic, delegate to one service, return response DTOs
- **Services**: Orchestrate workflows — one public method per use case, own `@Transactional`, constructor injection
- **Repositories**: Data access only — Spring Data conventions, `@Query` for joins, no business logic
- **Clients**: External boundary wrappers — interface is the contract, one method per operation, result records
- **File size**: Evaluate at 300–500 lines whether the class is properly decomposed
- **Method size**: Most 20–30 lines; orchestration up to 100; evaluate over 100

## Testing Patterns

- **Unit tests** (`*Test`): No Spring context. For pure business logic (e.g., `PricingCalculatorTest`).
- **Integration tests** (`*IntegrationTest`): Full Spring Boot app + Postgres via docker-compose + test doubles.
- **Database**: Real Postgres via `docker-compose up -d`. Tests connect to it — no Testcontainers.
- **AWS services** (SQS, SNS, S3): Real implementations run in tests. Test implementations of the AWS SDK clients intercept calls, validate inputs, and record invocations.
- **Non-AWS services** (Stripe, Inventory): Interface-level test doubles (`TestPaymentClient`, `TestInventoryClient`) replace the implementation.
- **OrderClient**: Typed REST client using Spring's `RestClient` and the same domain models as the controller.
- **BaseIntegrationTest** provides shared infrastructure: test double wiring, `OrderClient`, automatic reset between tests.
- **Test isolation**: Random IDs, fresh data per test, contextual domain in `@BeforeEach`.
- Tests live in the **same package** as the class they test.

## Documentation

Detailed documentation lives in `docs/`:
- `docs/ARCHITECTURE.md` — System architecture, component design, domain workflows
- `docs/TESTING.md` — Testing strategy, infrastructure, conventions
- `docs/DATABASE.md` — Schema, data models, relationships
- `docs/MIGRATIONS.md` — Flyway migration strategy
- `docs/RELEASE.md` — Release process
- `docs/SECURITY.md` — Application security design
- `docs/HOW_TO.md` — Detailed setup, configuration, gotchas, troubleshooting
- `docs/feature/ORDER_CREATION.md` — Order creation workflow (main feature)
- `docs/feature/ORDER_RETRIEVAL.md` — Order retrieval

## Key Files

- `docker-compose.yml` — Postgres for integration tests
- `support/BaseIntegrationTest.java` — Base class for all integration tests
- `support/TestConfiguration.java` — Spring test config that wires test doubles
- `support/clients/OrderClient.java` — Typed HTTP client for test use
- `support/clients/TestPaymentClient.java`, `TestInventoryClient.java` — Interface-level test doubles
- `support/aws/clients/TestSqsClient.java`, `TestSnsClient.java`, `TestS3Client.java` — AWS SDK test doubles

## Conventions

- **Naming**: `*Service` for orchestrators, `*Client` for external boundaries, descriptive names for standalone logic (`*Calculator`, `*Engine`)
- **Entities**: `with*` fluent mutators instead of public setters; pure computation returns result records
- **Packages**: Named after domain concepts, not technologies (`payment/` not `stripe/`); 5–8 file limit
- **Methods**: Prefer composition over inheritance; overloads delegate to the fuller signature
- Interfaces are the contract boundary for external services
- AWS implementations use real code with test SDK clients injected; Stripe and InventoryClient use `@Profile("!test")`
- Prefer asserting on observable side effects (HTTP response, DB state, SDK call recordings) over internal implementation details
- Java 25 (GraalVM CE)

## Skills

Available skills in `.claude/skills/`:

### Production
- **project-structure** — Domain-oriented package layout, 5–8 file package constraint
- **component-design** — Controllers, services, repositories, clients, pub/sub — responsibility, method sizing, composition
- **naming-conventions** — Class suffix rules (`*Service`, `*Client`, `*Calculator`)
- **entity-design** — JPA entities with `with*` fluent mutators, result records for computation
- **dependency-injection** — Constructor injection only, no inline dependency construction, `@Bean` methods own object creation
- **error-handling** — Minimal exception hierarchy (abstract `OrderingException` base + 4 subclasses), centralized `@ControllerAdvice` maps exception type to HTTP status, context maps for structured error details
- **logging** — SLF4J + Logback, structured JSON output, `@Redacted` annotation for sensitive field protection, log level guidelines
- **tracing** — Distributed tracing via Micrometer Tracing, correlation ID propagation (HTTP auto, SQS/SNS via message attributes), `TraceAttributes`/`TraceContext` utilities

### Project
- **project-documentation** — Required documentation structure (README, docs/, feature docs)

### Testing
- **adding-integration-tests** — Integration tests for API endpoints
- **adding-unit-tests** — Unit tests for pure business logic
- **test-data-isolation** — Test independence via random IDs and fresh data per test
- **adding-flyway-migrations** — Database migrations + schema test
- **integrating-external-sdk** — SDK-level test doubles (AWS)
- **integrating-external-sdk-no-interface** — Simulator pattern for SDK clients with no interface
- **integrating-external-api** — Interface-level test doubles (REST APIs)
- **setting-up-docker-for-tests** — Docker infrastructure for tests
