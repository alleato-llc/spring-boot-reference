# Architecture

## System overview

A Spring Boot order management system that demonstrates testing patterns. The system accepts orders via REST API and orchestrates payment, inventory, invoicing, notification, and fulfillment through external service boundaries.

```
Client (HTTP)
    │
    ▼
OrderController          ← thin, delegates to service
    │
    ▼
OrderService             ← orchestrates the full workflow
    │
    ├── PricingCalculator    ← pure computation (subtotals, discounts)
    ├── OrderRepository      ← Postgres via Spring Data JPA
    ├── PaymentClient        ← Stripe (interface boundary)
    ├── InventoryClient      ← external REST API (interface boundary)
    ├── InvoiceService       ← generates + stores via DocumentClient
    │       └── DocumentClient   ← S3 (interface boundary)
    ├── NotificationClient   ← SNS (interface boundary)
    └── FulfillmentClient    ← SQS (interface boundary)
```

## Component responsibilities

| Component | Role | Dependencies |
|---|---|---|
| **OrderController** | HTTP request/response mapping | OrderService |
| **OrderService** | Workflow orchestration, transaction management | All clients + repository |
| **PricingCalculator** | Price calculation, discounts | None (pure computation) |
| **OrderRepository** | Data access (Spring Data JPA) | Postgres |
| **PaymentClient** | Payment processing boundary | Stripe (prod) / TestPaymentClient (test) |
| **InventoryClient** | Inventory reservation boundary | REST API (prod) / TestInventoryClient (test) |
| **InvoiceService** | Invoice generation + storage orchestration | DocumentClient |
| **DocumentClient** | Document storage boundary | S3 (prod) / TestS3Client (test) |
| **NotificationClient** | Event publishing boundary | SNS (prod) / TestSnsClient (test) |
| **FulfillmentClient** | Job enqueueing boundary | SQS (prod) / TestSqsClient (test) |

## Package structure

Packages are named after **domain concepts**, not technologies:

- `controller/` — REST API layer
- `service/` — orchestration layer
- `models/` — domain entities, DTOs, enums
- `repository/` — data access
- `pricing/` — pure computation (standalone logic)
- `payment/` — payment processing boundary
- `inventory/` — inventory management boundary
- `invoicing/` — invoice generation + document storage
- `notification/` — event publishing boundary
- `fulfillment/` — job enqueueing boundary

Each supporting subdomain (`payment/`, `inventory/`, etc.) is a flat package containing an interface, a production implementation, and optionally a payload/event record.

## Design decisions

### Interface boundaries for external services

Every external service sits behind an interface. Production implementations are wired via Spring profiles or standard `@Bean` methods. Test doubles implement the same interface.

- **AWS SDK services** (SQS, SNS, S3): Test doubles implement the SDK client interface directly (`SqsClient`, `SnsClient`, `S3Client`). No `@Profile` needed — the test `@Bean` takes precedence.
- **Non-SDK services** (Stripe, Inventory API): Custom interfaces (`PaymentClient`, `InventoryClient`). Production impls use `@Profile("!test")`. Test doubles are wired in `TestConfiguration`.

### Transaction boundary

`OrderService.createOrder()` is `@Transactional`. If any step fails after the order is persisted, the transaction rolls back. External service calls (payment, SQS, SNS, S3) happen within the transaction — this is a deliberate simplification for a reference project.

### Pricing as pure computation

`PricingCalculator` has no dependencies — it takes line items and a promo code, returns a `PricingResult`. This makes it trivially unit-testable without Spring context.

## Domain workflow

See [Order Creation feature doc](feature/ORDER_CREATION.md) for the complete workflow.

## Technology choices

| Technology | Purpose | Why |
|---|---|---|
| Spring Boot 3.4 | Application framework | Industry standard for Java web apps |
| Spring Data JPA | ORM / data access | Reduces boilerplate, convention-based queries |
| Flyway | Database migrations | Reliable, SQL-based, integrates with Spring Boot |
| PostgreSQL | Database | Production-grade RDBMS, Docker-friendly for tests |
| AWS SDK v2 | Cloud service integration | Standard SDK for SQS, SNS, S3 |
| Java 25 (GraalVM CE) | Runtime | Latest LTS-track with modern language features |
