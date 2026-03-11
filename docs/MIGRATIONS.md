# Migrations

## Overview

Database migrations are managed by [Flyway](https://flywaydb.org/), integrated via Spring Boot. Migrations run automatically on application startup (including test startup).

## Configuration

Flyway is configured in `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
```

Migration files live in `src/main/resources/db/migration/`.

## Naming convention

```
V{version}__{description}.sql
```

- **Version**: Sequential integer (`V1`, `V2`, `V3`, ...)
- **Separator**: Double underscore `__`
- **Description**: Snake case, describes the change

Examples:
- `V1__create_orders_schema.sql`
- `V2__add_order_notes.sql`
- `V3__add_inventory_reservation_id.sql`

## Creating a new migration

1. Determine the next version number (check existing files in `src/main/resources/db/migration/`)
2. Create the file: `V{N}__{description}.sql`
3. Write the SQL (DDL only — no DML in migrations)
4. Run the schema test to verify:
   ```bash
   docker-compose up -d
   ./gradlew test --tests FlywayMigrationIntegrationTest
   ```

## Current migrations

| Version | File | Description |
|---------|------|-------------|
| V1 | `V1__create_orders_schema.sql` | Creates `orders` and `order_line_items` tables with indexes |
| V2 | `V2__add_order_notes.sql` | Adds `notes` column to `orders` |
| V3 | `V3__add_inventory_reservation_id.sql` | Adds `inventory_reservation_id` column to `orders` |

## Rollback strategy

Flyway Community Edition does not support rollback. If a migration needs to be undone:

1. Create a new migration that reverses the change (e.g., `V4__remove_notes_column.sql`)
2. Never modify an existing migration file — Flyway checksums will fail

## Testing

The `FlywayMigrationIntegrationTest` verifies that all migrations apply cleanly against real Postgres. This test runs as part of the standard test suite.

## Common pitfalls

- **Never modify a committed migration** — Flyway validates checksums. Changing a migration that has already been applied will cause startup failure.
- **Use `IF NOT EXISTS`/`IF EXISTS`** — makes migrations idempotent where possible.
- **No DML in migrations** — seed data belongs in test setup, not migrations. Migrations define structure only.
- **Test against real Postgres** — H2/HSQLDB syntax differences can hide issues. This project uses Docker Postgres for tests.
