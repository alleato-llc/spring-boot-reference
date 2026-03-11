---
name: entity-design
description: Design patterns for JPA entities — fluent with* mutators instead of setters, pure computation returning result records, and constructor-based initialization. Use when creating or modifying JPA entities.
---

# Entity Design

## Principles

JPA entities are mutable by necessity — Hibernate tracks and flushes changes to managed instances. But we can design the API to **communicate immutable intent** while keeping JPA compatibility.

## Fluent mutators (`with*`)

Replace public setters with `with*` methods that set the field and return `this`. This enables fluent chaining and makes mutation sites explicit:

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private OrderStatus status;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String paymentTransactionId;

    // --- Getters (read-only access) ---

    public Long getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getSubtotal() { return subtotal; }
    // ...

    // --- Fluent mutators (set field, return this) ---

    public Order withStatus(OrderStatus status) {
        this.status = status;
        return this;
    }

    public Order withSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
        return this;
    }

    public Order withDiscount(BigDecimal discount) {
        this.discount = discount;
        return this;
    }

    public Order withTotal(BigDecimal total) {
        this.total = total;
        return this;
    }

    public Order withPaymentTransactionId(String paymentTransactionId) {
        this.paymentTransactionId = paymentTransactionId;
        return this;
    }
}
```

### Why `with*` and not setters?

- **Intent**: `with*` communicates "I'm building up this object's state" — setters are just raw mutation
- **Chaining**: Multiple state changes read as a single logical operation
- **Distinction from getters**: `.status()` reads, `.withStatus(...)` writes — no ambiguity
- **JPA compatible**: The same object instance is mutated, so Hibernate's change tracking works normally

### Why not return a copy?

JPA's `EntityManager` tracks specific object instances. If `with*` returned a new copy, the copy wouldn't be the managed entity — JPA would lose track of changes. Returning `this` keeps JPA happy while giving us the fluent API.

### Usage in orchestrators

```java
order.withStatus(OrderStatus.CONFIRMED)
     .withPaymentTransactionId(result.transactionId());

// or across multiple steps
order.withInventoryReservationId(confirmation.reservationId());
```

## Constructor-based initialization

Set required state in the constructor. Use defaults for fields that are populated later in the workflow:

```java
public Order(String customerId) {
    this.customerId = customerId;
    this.status = OrderStatus.PENDING;
    this.subtotal = BigDecimal.ZERO;
    this.discount = BigDecimal.ZERO;
    this.total = BigDecimal.ZERO;
    this.createdAt = Instant.now();
}
```

JPA requires a no-arg constructor — make it `protected` so it's not used by application code:

```java
protected Order() {}
```

## Domain methods on entities

Entities can have methods that encapsulate domain logic — adding child entities, computing derived values, etc.:

```java
public void addLineItem(String productId, String productName, int quantity, BigDecimal unitPrice) {
    OrderLineItem item = new OrderLineItem(this, productId, productName, quantity, unitPrice);
    lineItems.add(item);
}
```

Keep these focused on the entity's own state. Logic that requires external dependencies belongs in a `*Service`.

## Pure computation: result records

When logic computes values from entity state, extract it into a standalone class that returns a result record — don't mutate the entity from inside the computation:

```java
// Result record — immutable, carries the computation output
public record PricingResult(BigDecimal subtotal, BigDecimal discount, BigDecimal total) {}

// Calculator — pure function, no entity mutation
@Service
public class PricingCalculator {
    public PricingResult calculate(List<OrderLineItem> lineItems, String promoCode) {
        // ... compute subtotal, discount, total
        return new PricingResult(subtotal, discount, total);
    }
}

// Orchestrator applies the result
PricingResult pricing = pricingCalculator.calculate(order.getLineItems(), request.promoCode());
order.withSubtotal(pricing.subtotal())
     .withDiscount(pricing.discount())
     .withTotal(pricing.total());
```

### Why separate computation from mutation?

- **Testability**: The calculator is a pure function — unit tests give it inputs and assert on outputs. No entity setup, no JPA.
- **Clarity**: The orchestrator (`OrderService`) is the only place that mutates the entity. The reader sees all state changes in one place.
- **Reusability**: The same calculator can be used for price previews, quotes, etc. without creating or modifying an entity.

## Checklist

When creating or modifying a JPA entity:

- [ ] Required fields set in the public constructor with sensible defaults
- [ ] No-arg constructor is `protected` (JPA only)
- [ ] No public setters — use `with*` fluent mutators
- [ ] `with*` methods return `this` (not a copy)
- [ ] Domain methods on the entity only modify its own state
- [ ] External computation returns result records, applied by the orchestrator
- [ ] Getters only — no `set*` methods exposed

## Reference

- `src/main/java/.../models/Order.java` — `with*` fluent mutators, constructor initialization
- `src/main/java/.../pricing/PricingCalculator.java` — Pure computation returning `PricingResult`
- `src/main/java/.../pricing/PricingResult.java` — Result record
- `src/main/java/.../service/OrderService.java` — Orchestrator applying results via `with*`
