---
name: test-data-isolation
description: Ensures integration tests are independent by using random IDs and fresh data per test. Tests must not depend on data from other tests. Use when writing or reviewing integration tests.
---

# Test Data Isolation

Each integration test must be fully independent — it creates its own data, uses random identifiers, and never assumes or depends on state left by other tests.

## Principles

### 1. Every test creates its own data

Tests must not read, reference, or depend on data created by other tests. Each test arranges its own inputs and asserts only on the outputs it produces.

```java
// Bad — depends on an order created by another test
OrderResponse fetched = orderClient.getOrder(1L);

// Good — creates its own order, then fetches it
OrderResponse created = orderClient.createOrder(request);
OrderResponse fetched = orderClient.getOrder(created.id());
```

### 2. Use random identifiers

All IDs that a test controls (customer IDs, product IDs, external reference IDs) must be random. This prevents accidental coupling between tests and ensures tests pass regardless of execution order.

Use `UUID.randomUUID()` for string identifiers:

```java
private String randomId() {
    return UUID.randomUUID().toString();
}

private String randomId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
}
```

### 3. Distinguish domain data from contextual data

**Domain data** is what the test is exercising — the entity being created, modified, or queried. Each test creates its own.

**Contextual data** is the prerequisite state that must exist for the domain operation to succeed — customers, product catalogs, configuration. If the system requires contextual entities, initialize them in test setup (`@BeforeEach`) so every test starts with a valid context.

```java
// Contextual data — initialized per test with random IDs
private String customerId;

@BeforeEach
void setUp() {
    customerId = randomId("cust");
}

@Test
void createsOrder() {
    // Domain data — created by this test, references contextual data
    var request = createSimpleOrderRequest(customerId, null,
            createLineItemRequest(randomId("prod"), "Widget", 2, "25.00"));
    OrderResponse order = orderClient.createOrder(request);
    assertThat(order.customerId()).isEqualTo(customerId);
}
```

If the contextual data varies between tests in a `@Nested` class, initialize it in that class's own `@BeforeEach`.

### 4. Test doubles reset between tests

`BaseIntegrationTest` resets all test doubles in `@BeforeEach`. This means each test starts with a clean slate for SDK call recordings and configurable behaviors. Combined with random IDs, this guarantees full isolation.

### 5. Never hardcode IDs

Hardcoded IDs create hidden coupling. Even if tests appear independent, hardcoded IDs can cause:
- **Flaky tests** when execution order changes
- **False positives** when a test accidentally reads another test's data
- **Cascading failures** when one test's data pollutes another's assertions

```java
// Bad — hardcoded IDs risk collision
var request = createSimpleOrderRequest("cust-123", null,
        createLineItemRequest("prod-1", "Widget", 1, "50.00"));

// Good — random IDs guarantee isolation
var request = createSimpleOrderRequest(randomId("cust"), null,
        createLineItemRequest(randomId("prod"), "Widget", 1, "50.00"));
```

## Helper method pattern

Design `create*` helper methods so random IDs are the default. Only accept explicit IDs when the test needs to assert on a specific value.

```java
// Default: random IDs — use when the ID value doesn't matter
private CreateOrderRequest createSimpleOrderRequest(String customerId, String promoCode,
                                                     LineItemRequest... items) {
    return new CreateOrderRequest(customerId, List.of(items), promoCode);
}

private LineItemRequest createLineItemRequest(String productId, String name,
                                               int qty, String price) {
    return new LineItemRequest(productId, name, qty, new BigDecimal(price));
}

// Caller generates the random ID
var request = createSimpleOrderRequest(randomId("cust"), null,
        createLineItemRequest(randomId("prod"), "Widget", 2, "25.00"));
```

## What about database cleanup?

Tests should assume data is recreated fresh but also always create new data. Because:
- Random IDs mean no collisions between tests
- Test doubles are reset between tests
- Database rows from previous tests exist but are invisible — each test only queries data it created

If your domain requires stricter isolation (e.g., unique constraints on business keys), consider using `@Transactional` test rollback or truncating tables in `@BeforeEach`. For this project, random IDs provide sufficient isolation.

## Checklist

When writing or reviewing integration tests, verify:

- [ ] Each test creates its own domain data (orders, etc.)
- [ ] All IDs (customer, product, etc.) are random — no hardcoded strings
- [ ] Contextual data (customers, config) is initialized in `@BeforeEach`
- [ ] No test reads or references data created by another test
- [ ] Assertions reference the test's own request/response — not hardcoded expected values
- [ ] Test doubles are reset between tests (handled by `BaseIntegrationTest`)

