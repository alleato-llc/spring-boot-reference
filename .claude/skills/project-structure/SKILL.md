---
name: project-structure
description: Organizes a Spring Boot project into domain-oriented packages. Core domain uses layered packages (controller/service/models/repository), supporting subdomains are flat packages. Use when creating a new project, adding a new domain area, or restructuring packages.
---

# Project Structure

## Philosophy

Organize packages to reflect the **domain**, not technical layers. The core domain (what the app is about) keeps a layered structure for clarity. Supporting subdomains (external integrations, utilities) are flat packages — they're simple enough that layers add overhead without value.

## Core domain layout

The core domain is the primary business capability — the reason the app exists. It uses layered packages because it has enough complexity (API, orchestration, persistence, models) to benefit from separation:

```
com.example.ordering/
├── controller/         REST API — receives requests, returns responses
├── service/            Orchestrators — coordinate multiple components
├── models/             Domain entities, DTOs, value objects, enums
└── repository/         Data access (Spring Data JPA)
```

Rules:
- **One core domain per application.** If you have two core domains, consider separate apps or a modular monolith.
- **Models shared across subdomains** live in the core `models/` package. If a model is only used within a subdomain, it can live in that subdomain's package.

## Supporting subdomain layout

Supporting subdomains provide capabilities the core domain depends on — payment processing, notifications, inventory. They're flat packages (no internal `controller/service/models` layers):

```
com.example.ordering/
├── payment/
│   ├── PaymentClient.java              (interface — contract boundary)
│   └── StripePaymentClient.java        (implementation)
├── notification/
│   ├── NotificationClient.java         (interface)
│   └── SnsNotificationClient.java      (implementation)
├── fulfillment/
│   ├── FulfillmentClient.java          (interface)
│   ├── FulfillmentPayload.java         (payload record)
│   └── SqsFulfillmentClient.java       (implementation)
├── inventory/
│   ├── InventoryClient.java            (interface + nested types)
│   └── HttpInventoryClient.java        (implementation)
├── invoicing/
│   ├── InvoiceService.java             (orchestrates generation + storage)
│   ├── DocumentClient.java             (interface — S3)
│   └── S3DocumentClient.java           (implementation)
└── pricing/
    ├── PricingCalculator.java          (pure computation)
    └── PricingResult.java              (result record)
```

Rules:
- **Name the package after the domain concept**, not the technology: `payment/` not `stripe/`, `notification/` not `sns/`.
- **Keep it flat.** If a subdomain needs its own `models/` subpackage, it's a sign the subdomain is complex enough to be its own module or service.
- **Interfaces live in the subdomain package.** The interface is the contract boundary — implementations sit next to it.
- **Payload/event records** (e.g., `FulfillmentPayload`, `OrderConfirmedEvent`) belong in the subdomain that produces them — they're part of that boundary's contract.

## Package size constraint

A package should contain **no more than 5–8 files**. When a package reaches this threshold, evaluate whether it actually represents multiple domains or subdomains that should be split.

### How to evaluate

When a package grows past 5–8 files, ask:

1. **Can the files be grouped by a domain concept?** If you see clusters of files that relate to different responsibilities, they're likely separate subdomains. Extract each cluster into its own package named after the concept.

2. **Are there files that only talk to each other?** Files that form a self-contained group (interface + implementation + payload record) are a subdomain boundary waiting to be extracted.

3. **Is there a valid reason to keep them together?** Some packages genuinely need more files — a `models/` package with 10 entity classes is fine because they all serve the same purpose. The constraint is a trigger to evaluate, not a hard limit.

### Example

A `gateway/` package with 8 files:

```
gateway/
├── PaymentClient.java
├── StripePaymentClient.java
├── NotificationClient.java
├── SnsNotificationClient.java
├── FulfillmentClient.java
├── SqsFulfillmentClient.java
├── InventoryClient.java
└── HttpInventoryClient.java
```

This is actually 4 separate domain concepts. Refactor to:

```
payment/
├── PaymentClient.java
└── StripePaymentClient.java
notification/
├── NotificationClient.java
└── SnsNotificationClient.java
fulfillment/
├── FulfillmentClient.java
└── SqsFulfillmentClient.java
inventory/
├── InventoryClient.java
└── HttpInventoryClient.java
```

Each package is now 2–3 files, clearly scoped to one domain concept.

## When to add layers vs keep flat

| Signal | Approach |
|---|---|
| Has its own API endpoint | Layered — needs controller/service/models |
| Wraps an external service | Flat — interface + implementation |
| Pure computation (no dependencies) | Flat — class + result record |
| Has a few models used only internally | Flat — models live in the package |
| Has many models, complex relationships | Consider `models/` subpackage or extract to a module |

## Test structure

Tests mirror the production package structure. Shared test infrastructure lives in `support/`:

```
src/test/java/com/example/ordering/
├── support/                    Shared test infrastructure
│   ├── BaseIntegrationTest.java
│   ├── TestConfiguration.java
│   ├── clients/                Typed test clients and interface-level doubles
│   └── aws/clients/            AWS SDK-level test doubles
├── controller/                 Tests for controller (integration tests)
├── repository/                 Tests for repository/migrations
└── pricing/                    Tests for pricing (unit tests)
```

Rules:
- Tests live in the **same package** as the class they test.
- Test support code lives in `support/` with subpackages as needed.
- `clients/` holds typed HTTP clients (e.g., `OrderClient`) and interface-level test doubles.
- `aws/clients/` holds AWS SDK-level test doubles.

## Adding a new subdomain

1. Create a package named after the domain concept: `src/main/java/.../shipping/`
2. Define the interface (contract boundary): `ShippingClient.java`
3. Add the implementation: `FedExShippingClient.java`
4. Add any payload/event records: `ShipmentRequest.java`
5. Wire the test double in `TestConfiguration`
6. Add the test double field + reset in `BaseIntegrationTest`

Do not create `controller/`, `service/`, `models/` subpackages within the subdomain unless it genuinely needs that complexity.

## Reference

See the project structure in `src/main/java/com/alleato/ecommerce/ordering/` for a complete example.
