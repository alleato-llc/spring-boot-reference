---
name: setting-up-docker-for-tests
description: Sets up Docker-managed Postgres for integration tests. Tests expect the database to already be running — locally via docker-compose, in CI via service containers. No Testcontainers. Use when bootstrapping test infrastructure or adding a database dependency to tests.
---

# Setting Up Docker for Tests

## What to dockerize

Docker is for **direct, stateful dependencies where you need to test the real interaction**. The decision depends on how complex the interaction is and whether it's truly part of your service's domain.

### Decision framework

Ask these questions for each dependency:

**1. Is it your service's own data store?**

If yes, dockerize it. Your database, your schema, your migrations — these must be tested against the real engine. Postgres-specific syntax, index behavior, and migration compatibility can't be verified any other way.

**2. How complex is the interaction?**

| Interaction | Approach |
|---|---|
| Custom queries, stored procedures, migrations | Dockerize — must test the real thing |
| Complex data structures (e.g., Redis sorted sets, Lua scripts, Elasticsearch custom analyzers) | Dockerize — a test double can't faithfully reproduce this behavior |
| Vanilla get/set, simple key-value, basic CRUD | Test double may suffice — but you lose boundary testing |

For example: if you're using Redis as a simple cache (get/set with TTL), a test double works. If you're using Redis sorted sets with custom scoring or Lua scripts, dockerize it — the interaction is too complex to fake reliably.

**3. Is it really part of your domain?**

If your service handles orders AND search, ask: is this one domain or two? Signs it's two:

- The search index has its own data model separate from orders
- Search could be its own service
- The interaction between orders and search is an event (e.g., "order created" published to SNS/SQS)

If it's two domains in a monolith, use **separate test suites**:
- **Order tests**: dockerize Postgres, use test doubles for everything else. Verify you publish the right events (assert on `TestSnsClient` recordings).
- **Search tests**: dockerize Elasticsearch with seeded data, test that queries return expected results.
- **The boundary between them**: validated by asserting that order tests publish correct events — the contract. Search tests verify the consumer side independently.

This keeps each test suite focused and fast. You're not standing up Postgres + Elasticsearch + Redis just to test order creation.

**4. Is your docker-compose growing?**

If your docker-compose has more than one or two services, it's a signal:
- The application may be too complex — consider whether it's really multiple domains
- Some dependencies should be test doubles, not containers
- You may need separate test suites for separate concerns

### Summary

**Dockerize:**
- Your own database (Postgres, MySQL) — schema migrations require the real engine
- Dependencies with complex interactions (custom Redis commands, Elasticsearch analyzers) — test doubles can't reproduce them

**Use test doubles instead:**
- External services with public SDKs (AWS, Stripe) — see `integrating-external-sdk`
- External APIs you call over HTTP — see `integrating-external-api`
- Simple cache interactions (vanilla Redis get/set)
- Other team services or internal APIs

**Separate test suites when:**
- Your monolith spans multiple domains (orders + search + analytics)
- Each domain has its own infrastructure needs (Postgres vs Elasticsearch)
- The boundary between domains is an event or message — test the contract (correct event published), not the end-to-end flow

## How it works

Integration tests connect to a real Postgres instance. The tests don't manage the container lifecycle — they just expect it to be running. This keeps the test code simple and the infrastructure concern separate.

## Why not Testcontainers?

Testcontainers starts and stops Docker containers programmatically from test code. This adds:
- A library dependency to maintain
- API surface area in your test infrastructure
- Complexity in the test base class (container lifecycle, dynamic property wiring)
- Slower test startup (container pull + boot on first run)

The simpler approach: **tests just connect to a known database URL**. How the database gets started is a deployment/operations concern, not a test concern.

## Setup

### 1. docker-compose.yml

Provide a `docker-compose.yml` at the project root so developers can start the database with one command.

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: orders_test
      POSTGRES_USER: test
      POSTGRES_PASSWORD: test
    ports:
      - "15432:5432"
```

Use a non-default port (e.g., 15432) to avoid conflicts with other Postgres instances.

### 2. application-test.yml

Configure the test profile to connect to the docker-compose Postgres.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/orders_test
    username: test
    password: test
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    clean-disabled: false
```

### 3. BaseIntegrationTest

The base class activates the `test` profile. No container management code needed.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfiguration.class)
public abstract class BaseIntegrationTest {
    // No Testcontainers — just connect to the already-running Postgres
}
```

### 4. Running locally

```bash
docker-compose up -d    # Start Postgres
./gradlew test          # Run tests
docker-compose down     # Stop when done
```

### 5. CI — GitHub Actions

```yaml
services:
  postgres:
    image: postgres:16-alpine
    env:
      POSTGRES_DB: orders_test
      POSTGRES_USER: test
      POSTGRES_PASSWORD: test
    ports:
      - 15432:5432
    options: >-
      --health-cmd pg_isready
      --health-interval 10s
      --health-timeout 5s
      --health-retries 5

steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with:
      java-version: '25'
      distribution: 'graalvm'
  - run: ./gradlew test
```

### 6. CI — GitLab CI

```yaml
test:
  services:
    - name: postgres:16-alpine
      alias: postgres
      variables:
        POSTGRES_DB: orders_test
        POSTGRES_USER: test
        POSTGRES_PASSWORD: test
  variables:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orders_test
    SPRING_DATASOURCE_USERNAME: test
    SPRING_DATASOURCE_PASSWORD: test
  script:
    - ./gradlew test
```

In GitLab CI, services are accessed by alias (not localhost), so override the datasource URL via environment variable.

## Key principles

- **Tests don't manage infrastructure** — they connect to what's already running
- **docker-compose for local dev** — one command to start, one to stop
- **CI service containers** — native support in GitHub Actions and GitLab CI
- **Flyway handles schema** — migrations run automatically on test startup
- **Non-default port** — avoids conflicts with other databases on the developer's machine

## Reference

- `docker-compose.yml` — Local Postgres setup
- `src/test/resources/application-test.yml` — Test database configuration
- `src/test/java/.../support/BaseIntegrationTest.java` — Base class (no container code)
