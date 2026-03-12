# How To

## Setup

### Prerequisites

1. **Java 25+** (GraalVM CE recommended)
   ```bash
   sdk install java 25.0.2-graalce
   ```

2. **Docker** (for Postgres)
   ```bash
   # Verify Docker is running
   docker info
   ```

### First-time setup

```bash
# Clone the repository
git clone <repo-url>
cd spring-boot-testing-reference

# Start Postgres
docker-compose up -d

# Verify tests pass
./gradlew test

# Stop Postgres when done
docker-compose down
```

### IDE setup

The project uses standard Gradle structure. Import as a Gradle project in IntelliJ IDEA or VS Code.

- **IntelliJ**: File > Open > select `build.gradle`
- **VS Code**: Install Java Extension Pack, open the project directory

## Common operations

### Running tests

```bash
# All tests
./gradlew test

# Specific test class
./gradlew test --tests OrderApiIntegrationTest

# Specific test method (nested class)
./gradlew test --tests "OrderApiIntegrationTest\$CreateOrderSuccess"

# With verbose output
./gradlew test --info
```

### Adding a new external service integration

1. Create the client interface in a domain package (e.g., `shipping/ShippingClient.java`)
2. Create the production implementation (e.g., `shipping/FedExShippingClient.java`)
3. Create a test double (e.g., `support/clients/TestShippingClient.java`)
4. Wire in `TestConfiguration`
5. Add to `BaseIntegrationTest` (field + reset)
6. See the `testing-boundaries` skill for detailed steps

### Adding a new database column

1. Create a new Flyway migration: `src/main/resources/db/migration/V{N}__description.sql`
2. Update the JPA entity with the new field
3. Add a `with*` fluent mutator (no public setter)
4. Run tests to verify: `./gradlew test`
5. See the `adding-flyway-migrations` skill for detailed steps

### Debugging test failures

1. Check Postgres is running: `docker-compose ps`
2. Check test output: `./gradlew test --info`
3. If Flyway fails: verify migration checksums haven't changed
4. If port conflict: check nothing else is on port 15432

## Configuration

### Application properties

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/orders` | Database URL |
| `spring.datasource.username` | `orders` | Database user |
| `spring.datasource.password` | `orders` | Database password |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate schema validation mode |
| `spring.flyway.enabled` | `true` | Enable Flyway migrations |
| `fulfillment.queue.url` | (configured in test) | SQS FIFO queue URL |
| `notification.topic.arn-prefix` | (configured in test) | SNS topic ARN prefix |

Test overrides are in `src/test/resources/application-test.yml`.

## Gotchas

### Postgres must be running before tests

Integration tests require Postgres. If you see `Connection refused` errors:
```bash
docker-compose up -d
# Wait a few seconds for Postgres to initialize
./gradlew test
```

### Never modify committed migrations

Flyway checksums will fail if you change an existing migration. Always create a new migration instead. If you need to fix a migration that hasn't been committed yet, drop and recreate the test database:
```bash
docker-compose down -v    # -v removes volumes
docker-compose up -d      # Fresh database
```

### Test doubles reset automatically

`BaseIntegrationTest` resets all test doubles in `@BeforeEach`. You don't need to reset them manually. If you're not extending `BaseIntegrationTest`, you must reset them yourself.

### @Profile("!test") vs no profile

- **`@Profile("!test")`**: Used for non-SDK services (Stripe, Inventory API) where the interface is custom. The test double is a different class wired in `TestConfiguration`.
- **No profile**: Used for AWS SDK services where the test double implements the SDK interface directly. Spring's `@TestConfiguration` bean takes precedence.

### Order of operations in OrderService

The order matters — if payment fails, downstream steps (inventory, invoice, notification, fulfillment) should not execute. Tests verify this explicitly with negative assertions.

## Troubleshooting

| Symptom | Cause | Solution |
|---|---|---|
| `Connection refused` on test startup | Postgres not running | `docker-compose up -d` |
| `Flyway checksum mismatch` | Modified a committed migration | Create a new migration instead, or `docker-compose down -v && docker-compose up -d` |
| `Port 15432 already in use` | Another Postgres instance | Stop the other instance or change the port in `docker-compose.yml` |
| Test passes locally, fails in CI | Missing Docker service | Ensure CI workflow includes Postgres service container |
| `@Bean method returned null` | Test double not wired | Check `TestConfiguration` has a `@Bean` for the missing type |
