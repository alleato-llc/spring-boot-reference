---
name: naming-conventions
description: Naming rules for Spring Boot classes — *Service for orchestrators, *Client for external service boundaries, descriptive names for standalone logic. Use when creating new classes or reviewing naming.
---

# Naming Conventions

## Class suffixes

| Suffix | When to use | Depends on other components? | Example |
|---|---|---|---|
| `*Service` | Orchestrates multiple components | Yes — repositories, clients, other services | `OrderService`, `InvoiceService` |
| `*Client` | Interface or implementation wrapping an external service | Yes — SDK client, HTTP client, or external API | `PaymentClient`, `StripePaymentClient`, `SqsFulfillmentClient` |
| `*Calculator`, `*Engine`, `*Validator`, etc. | Standalone logic — pure computation, validation, transformation | No — takes inputs, returns outputs | `PricingCalculator` |

### Key rule: `*Service` implies dependencies

A class named `*Service` tells the reader it orchestrates other components — it has constructor-injected dependencies. If a class performs self-contained logic with no dependencies (math, validation, formatting), do **not** name it `*Service`. Use a descriptive name that reflects what it does.

```java
// Bad — PricingService implies it depends on other components
@Service
public class PricingService {
    // But it has no dependencies — just math
    public PricingResult calculate(List<OrderLineItem> items, String promoCode) { ... }
}

// Good — PricingCalculator tells the reader it's standalone computation
@Service
public class PricingCalculator {
    public PricingResult calculate(List<OrderLineItem> items, String promoCode) { ... }
}
```

### External service implementations

Prefix implementations with what they integrate:

| Prefix | When | Example |
|---|---|---|
| `Stripe*` | Stripe SDK/API | `StripePaymentClient` |
| `Sns*` | AWS SNS | `SnsNotificationClient` |
| `Sqs*` | AWS SQS | `SqsFulfillmentClient` |
| `S3*` | AWS S3 | `S3DocumentClient` |
| `Http*` | Generic REST API (no SDK) | `HttpInventoryClient` |

The prefix makes it immediately clear what external system the implementation talks to, and distinguishes it from the interface (`PaymentClient` vs `StripePaymentClient`).

### Interfaces vs implementations

- **Interface**: Named after the domain concept — `PaymentClient`, `NotificationClient`, `FulfillmentClient`
- **Implementation**: Prefixed with the technology — `StripePaymentClient`, `SnsNotificationClient`
- The interface is the contract boundary. Code depends on the interface, not the implementation.

## Test class suffixes

| Suffix | Type | Spring context? | Example |
|---|---|---|---|
| `*Test` | Unit test | No | `PricingCalculatorTest` |
| `*IntegrationTest` | Integration test | Yes — full app + DB | `OrderApiIntegrationTest` |

### Test doubles

Prefix with `Test` — they are test implementations, not mocks:

| Name | What it replaces | Pattern |
|---|---|---|
| `TestPaymentClient` | `PaymentClient` interface | Interface-level double |
| `TestInventoryClient` | `InventoryClient` interface | Interface-level double |
| `TestSqsClient` | `SqsClient` SDK interface | SDK-level double |
| `TestSnsClient` | `SnsClient` SDK interface | SDK-level double |
| `TestS3Client` | `S3Client` SDK interface | SDK-level double |

## Method naming

### Helper methods in tests

Use `create*` prefix for test data builders to distinguish from production operations:

```java
// Builds a request object — does not call the API
private static CreateOrderRequest createSimpleOrderRequest(...) { ... }
private static LineItemRequest createLineItemRequest(...) { ... }
```

### Fluent mutators on entities

Use `with*` prefix for fluent mutators that set a field and return `this`:

```java
order.withStatus(OrderStatus.CONFIRMED)
     .withPaymentTransactionId(txnId);
```

The `with*` prefix distinguishes mutators from getters (`.status()` returns a value, `.withStatus(...)` sets one).

