---
name: integrating-external-api
description: Integrates an external service that has no public SDK — we define our own client interface with an HTTP implementation for production and an in-memory test implementation. Use when adding an integration with an external REST API, partner API, or internal service with no SDK.
---

# Integrating an External Service via Custom Client Interface

Use this pattern when the external service has **no public SDK** — you must build your own client. The key insight: define an interface as the contract boundary, provide an HTTP implementation for production, and an in-memory test implementation that records calls.

## Architecture

```
Client Interface            (contract boundary you define)
       |
   ┌───┴───┐
HttpImpl        TestImpl
(@Profile       (in-memory, records calls,
 "!test")        configurable behavior)
```

## Step-by-step

### 1. Define the client interface

Include request/response records and domain exceptions as nested types.

```java
// src/main/java/.../inventory/InventoryClient.java
public interface InventoryClient {
    ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items);

    record ReservationItem(String productId, int quantity) {}
    record ReservationConfirmation(String reservationId, String orderId) {}

    class InsufficientStockException extends RuntimeException {
        private final String productId;
        public InsufficientStockException(String productId) {
            super("Insufficient stock for product: " + productId);
            this.productId = productId;
        }
        public String getProductId() { return productId; }
    }
}
```

### 2. Create the HTTP implementation

Uses Spring `RestClient`. Annotated with `@Profile("!test")` so it's excluded in tests.

```java
// src/main/java/.../inventory/HttpInventoryClient.java
@Component
@Profile("!test")
public class HttpInventoryClient implements InventoryClient {
    private final RestClient restClient;

    public HttpInventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items) {
        return restClient.post()
                .uri("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReserveRequest(orderId, items))
                .retrieve()
                .body(ReservationConfirmation.class);
    }

    private record ReserveRequest(String orderId, List<ReservationItem> items) {}
}
```

### 3. Create the in-memory test implementation

Key responsibilities:
- **Record all calls** for test assertion
- **Configurable behavior** (success/failure scenarios)
- **Simulate failures** — configurable exception rules via `throwWhen`
- **Return realistic responses**
- **Provide a `reset()` method** for cleanup between tests

```java
// src/test/java/.../support/clients/TestInventoryClient.java
public class TestInventoryClient implements InventoryClient {
    private final Set<String> outOfStockProducts = new HashSet<>();
    private final List<ReservationCall> reservations = new ArrayList<>();
    private final List<ExceptionRule> exceptionRules = new ArrayList<>();
    private int reservationCounter = 0;

    @Override
    public ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items) {
        // Check exception rules first — simulates runtime failures
        var request = new ReservationRequest(orderId, List.copyOf(items));
        for (var rule : exceptionRules) {
            if (rule.condition().test(request)) {
                throw rule.exception().get();
            }
        }

        for (ReservationItem item : items) {
            if (outOfStockProducts.contains(item.productId())) {
                throw new InsufficientStockException(item.productId());
            }
        }
        reservationCounter++;
        String reservationId = "test-reservation-" + reservationCounter;
        reservations.add(new ReservationCall(orderId, List.copyOf(items), reservationId));
        return new ReservationConfirmation(reservationId, orderId);
    }

    // Test configuration
    public void setOutOfStock(String productId) { outOfStockProducts.add(productId); }
    public void setInStock(String productId) { outOfStockProducts.remove(productId); }

    // Simulate failures: condition evaluates the request, supplier creates the exception
    public void throwWhen(Predicate<ReservationRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptionRules.add(new ExceptionRule(condition, exception));
    }

    // Test assertions
    public List<ReservationCall> getReservations() { return Collections.unmodifiableList(reservations); }
    public ReservationCall getLastReservation() { return reservations.get(reservations.size() - 1); }
    public int getReservationCount() { return reservations.size(); }
    public void reset() { reservations.clear(); outOfStockProducts.clear(); exceptionRules.clear(); reservationCounter = 0; }

    /** The parameters passed to a reserveItems call, for use with throwWhen. */
    public record ReservationRequest(String orderId, List<ReservationItem> items) {}
    public record ReservationCall(String orderId, List<ReservationItem> items, String reservationId) {}
    private record ExceptionRule(Predicate<ReservationRequest> condition,
                                 Supplier<? extends RuntimeException> exception) {}
}
```

### 4. Register in TestConfiguration

```java
@Bean
public TestInventoryClient testInventoryClient() {
    return new TestInventoryClient();
}

@Bean
public InventoryClient inventoryClient(TestInventoryClient testImpl) {
    return testImpl;
}
```

### 5. Wire into BaseIntegrationTest and assert

```java
@Autowired
protected TestInventoryClient testInventoryClient;

// In tests:
assertThat(testInventoryClient.getReservationCount()).isEqualTo(1);
ReservationCall reservation = testInventoryClient.getLastReservation();
assertThat(reservation.orderId()).isEqualTo(String.valueOf(order.id()));
assertThat(reservation.items()).containsExactlyInAnyOrder(
        new ReservationItem("prod-1", 2),
        new ReservationItem("prod-2", 1));
```

### 6. Simulate failures

Use `throwWhen` to test how your code handles service failures. The predicate evaluates a `ReservationRequest` (bundling the method parameters); when it returns `true`, the supplied exception is thrown.

```java
// Simulate a total service outage
testInventoryClient.throwWhen(
        req -> true,
        () -> new RuntimeException("Connection refused"));

// Simulate failure for a specific order
testInventoryClient.throwWhen(
        req -> req.orderId().equals("42"),
        () -> new InventoryClient.InsufficientStockException("prod-1"));

// Simulate failure when a specific product is in the request
testInventoryClient.throwWhen(
        req -> req.items().stream().anyMatch(i -> i.productId().equals("prod-hazmat")),
        () -> new RuntimeException("Hazardous materials require manual review"));
```

Exception rules are checked before the built-in out-of-stock logic, and cleared on `reset()`.

Note: since you own the interface, the `ReservationRequest` record bundles the method parameters into a single object for the predicate. This parallels how the SDK pattern uses SDK request objects.

## When to use this vs `integrating-external-sdk`

| Scenario | Pattern |
|----------|---------|
| Service provides a public SDK (AWS, Stripe, Twilio) | `integrating-external-sdk` |
| Service has a REST API but no SDK | **This pattern** |
| Complex API with tricky serialization | Consider WireMock |

## Key differences from SDK pattern

- HTTP impl uses `@Profile("!test")` — it does NOT run in tests
- Test double implements your interface, not an SDK client interface
- You own the contract boundary (the interface), so you can design it for testability

## Reference examples

See these files in the project for complete working examples:
- `src/main/java/.../inventory/InventoryClient.java` — Client interface
- `src/main/java/.../inventory/HttpInventoryClient.java` — HTTP implementation
- `src/test/java/.../support/clients/TestInventoryClient.java` — In-memory test implementation
- `src/test/java/.../support/TestConfiguration.java` — Test bean wiring
