---
name: adding-flyway-migrations
description: Adds a Flyway database migration for schema changes. Creates a versioned SQL migration file and updates the FlywayMigrationIntegrationTest to verify the new column/table. Use when adding database columns, tables, or indexes.
---

# Adding Flyway Migrations

## Step-by-step

### 1. Create the migration file

Place in `src/main/resources/db/migration/` with versioned naming:

```
V{next_version}__{description}.sql
```

Check existing migrations to determine the next version number:
```bash
ls src/main/resources/db/migration/
```

Example migration:
```sql
-- V3__add_inventory_reservation_id.sql
ALTER TABLE orders ADD COLUMN inventory_reservation_id VARCHAR(255);
```

### 2. Add the JPA entity field

```java
@Column
private String inventoryReservationId;

public String getInventoryReservationId() { return inventoryReservationId; }
public void setInventoryReservationId(String id) { this.inventoryReservationId = id; }
```

### 3. Update FlywayMigrationIntegrationTest

Add the new column to the schema assertion in `FlywayMigrationIntegrationTest`:

```java
assertThat(orderColumns).contains("id", "customer_id", /* ... */, "new_column_name");
```

### 4. Run tests

```bash
./gradlew test
```

Postgres must be running (`docker-compose up -d`). Flyway applies migrations against the real database engine on test startup.

## Conventions

- Use `ALTER TABLE ... ADD COLUMN` for adding columns
- Use descriptive snake_case for column names
- JPA field names use camelCase (Hibernate auto-maps)
- Always verify with `FlywayMigrationIntegrationTest`

## Reference

- Migrations: `src/main/resources/db/migration/`
- Schema test: `src/test/java/.../repository/FlywayMigrationIntegrationTest.java`
