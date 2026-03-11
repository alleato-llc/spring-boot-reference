# Database

## Overview

PostgreSQL 16 (Alpine) managed via Docker for development and tests. Schema managed by Flyway migrations. JPA/Hibernate in `validate` mode — Hibernate verifies the schema matches entities but never modifies it.

## Connection

| Environment | Host | Port | Database | User | Password |
|---|---|---|---|---|---|
| Development | localhost | 5432 | orders | orders | orders |
| Test | localhost | 15432 | orders_test | test | test |

Test database is provisioned by `docker-compose.yml`.

## Schema

### orders

| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGSERIAL | PRIMARY KEY | Auto-generated ID |
| customer_id | VARCHAR(255) | NOT NULL | Customer identifier |
| status | VARCHAR(50) | NOT NULL | Order status (enum) |
| subtotal | NUMERIC(10,2) | | Pre-discount total |
| discount | NUMERIC(10,2) | | Discount amount |
| total | NUMERIC(10,2) | | Final total after discount |
| payment_transaction_id | VARCHAR(255) | | Payment provider transaction ID |
| inventory_reservation_id | VARCHAR(255) | | Inventory reservation ID |
| notes | TEXT | | Order notes |
| created_at | TIMESTAMP WITH TIME ZONE | DEFAULT NOW() | Creation timestamp |

Indexes:
- `idx_orders_customer_id` on `customer_id`
- `idx_orders_status` on `status`

### order_line_items

| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGSERIAL | PRIMARY KEY | Auto-generated ID |
| order_id | BIGINT | NOT NULL, FK -> orders(id) | Parent order |
| product_id | VARCHAR(255) | NOT NULL | Product identifier |
| product_name | VARCHAR(255) | NOT NULL | Product display name |
| quantity | INTEGER | NOT NULL | Quantity ordered |
| unit_price | NUMERIC(10,2) | NOT NULL | Price per unit |

Foreign key: `order_id` references `orders(id)` with `ON DELETE CASCADE`.

### budgets

Not applicable — this project does not have a budgets table. (Budgets exist in the Rust CLI reference project.)

## Entity relationships

```
orders (1) ──── (*) order_line_items
```

An order has many line items. Line items are cascade-deleted with their parent order.

## JPA entities

| Entity | Table | Key fields |
|---|---|---|
| `Order` | orders | Uses `with*` fluent mutators, `@OneToMany` to line items |
| `OrderLineItem` | order_line_items | Computed `getLineTotal()` method (quantity * unitPrice) |

## Data model notes

- **Money**: Stored as `NUMERIC(10,2)` in Postgres, mapped to `BigDecimal` in Java. Never use `float`/`double` for money.
- **Status**: Stored as VARCHAR, mapped to `OrderStatus` enum via JPA `@Enumerated(STRING)`.
- **Timestamps**: `TIMESTAMP WITH TIME ZONE` for all temporal columns.
- **IDs**: Database-generated `BIGSERIAL`. No application-generated UUIDs.

## OrderStatus values

| Status | Meaning |
|---|---|
| `PENDING` | Initial state, order created |
| `CONFIRMED` | Payment successful, order processing |
| `PAYMENT_FAILED` | Payment charge declined |
| `SHIPPED` | Fulfillment completed |
| `CANCELLED` | Order cancelled |
