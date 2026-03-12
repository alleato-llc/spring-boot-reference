---
name: component-design
description: Design guidelines for Spring Boot components — controllers, services, repositories, clients, and pub/sub abstractions. Covers file responsibility, method sizing, composition, overloading, and overriding. Use when creating or reviewing controllers, services, repositories, or client classes.
---

# Component Design

## Shared rules

These apply to all component types.

### Single responsibility first, then size

A class should be responsible for **one thing**. File size is a secondary signal — only evaluate it after confirming the class is properly decomposed.

If a class exceeds **300–500 lines**, evaluate whether it's doing too much. Ask:

1. Does this class have more than one reason to change?
2. Can the methods be grouped into clusters that serve different purposes?
3. Would extracting a cluster into its own class make both classes clearer?

If the answer to all three is no — the class is genuinely one cohesive responsibility that happens to be large — that's fine. The constraint is a trigger to evaluate, not a hard limit.

### Method size

Most methods should naturally land at **20–30 lines** when they're doing one thing well.

**Up to 100 lines** is acceptable for orchestration methods that coordinate a sequence of steps — each step is a clear block, and extracting them into private methods would just scatter the narrative.

**Over 100 lines** is the trigger to evaluate. Ask:
- Is this method doing more than one thing?
- Are there blocks of code that could be named (extracted into a method) to clarify the flow?
- Is there duplicated logic that could be shared?

### Method composition

Structure methods at **one level of abstraction**. A method should either coordinate high-level steps or implement low-level details — not both.

```java
// Bad — mixes orchestration with implementation details
public Order createOrder(CreateOrderRequest request) {
    Order order = new Order(request.customerId());
    for (var item : request.items()) {
        order.addLineItem(item.productId(), item.productName(), item.quantity(), item.unitPrice());
    }
    BigDecimal subtotal = order.getLineItems().stream()
            .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    // ... 80 more lines of inline computation and orchestration
}

// Good — orchestration at one level, details pushed down
public Order createOrder(CreateOrderRequest request) {
    Order order = buildOrder(request);

    PricingResult pricing = pricingCalculator.calculate(order.getLineItems(), request.promoCode());
    order.withSubtotal(pricing.subtotal())
         .withDiscount(pricing.discount())
         .withTotal(pricing.total());

    order = orderRepository.save(order);
    chargePayment(order);
    // ...
}
```

When an orchestration method is long but each step is clear and sequential, it's fine to keep it as one method. Extract when:
- A block of code needs a name to explain what it does
- The same logic appears in multiple places
- The method mixes abstraction levels

### Overloading

Use overloading for **default parameter patterns** — a simpler signature delegates to a fuller one:

```java
// Default: success status asserted automatically
public OrderResponse createOrder(CreateOrderRequest request) {
    return createOrder(request, HttpStatus.CREATED);
}

// Explicit: caller specifies expected status
public OrderResponse createOrder(CreateOrderRequest request, HttpStatus expectedStatus) {
    // ... full implementation
}
```

Rules:
- The simpler overload must delegate to the fuller one — no duplicated logic
- Overloads should differ by **what the caller controls**, not by what the method does
- If overloads would differ in behavior (not just defaults), use different method names instead

### Overriding

Prefer **composition over inheritance**. Use overriding only for:

- Framework extension points (Spring's `WebMvcConfigurer`, JPA's `@MappedSuperclass`)
- Template method patterns where the base class defines the skeleton and subclasses fill in steps
- Test base classes (`BaseIntegrationTest`)

If you're tempted to create a class hierarchy for code reuse, extract the shared logic into a collaborator and inject it instead:

```java
// Bad — inheritance for code reuse
public abstract class BaseOrderProcessor {
    protected void validateOrder(Order order) { ... }
}
public class DomesticOrderProcessor extends BaseOrderProcessor { ... }
public class InternationalOrderProcessor extends BaseOrderProcessor { ... }

// Good — composition via shared collaborator
public class DomesticOrderProcessor {
    private final OrderValidator validator;
    // ...
}
public class InternationalOrderProcessor {
    private final OrderValidator validator;
    // ...
}
```

## Controller

**Responsibility**: Receive HTTP requests, delegate to a service, return HTTP responses. Controllers are thin — they handle HTTP concerns and nothing else.

### Structure

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return OrderResponse.from(orderService.getOrder(id));
    }
}
```

### Rules

- **One Java method per HTTP endpoint** (unique HTTP method + path). If a method has conditional logic for different request shapes, split it into separate endpoints with distinct paths.
- **No business logic.** Validation annotations (`@Valid`, `@NotBlank`) are fine — computing discounts is not.
- **Delegate to one service.** If a controller method calls multiple services, the orchestration belongs in a service.
- **Return response types, not entities.** Map entities to response DTOs (records) at the controller boundary.
- **Exception handling via `@ExceptionHandler` or `@ControllerAdvice`** — not try/catch in every method.

### Method signatures

- Accept `@RequestBody` for POST/PUT, `@PathVariable` for path parameters, `@RequestParam` for query parameters
- Return `ResponseEntity<T>` when you need to control the status code, or just `T` for default 200 OK
- Use domain-specific request/response records — not `Map<String, Object>` or raw JSON strings

## Service

**Responsibility**: Orchestrate business workflows. A service coordinates multiple components (repositories, clients, other services) to fulfill a use case.

### Structure

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingCalculator pricingCalculator;
    private final PaymentClient paymentClient;
    // ... other dependencies

    public OrderService(OrderRepository orderRepository,
                        PricingCalculator pricingCalculator,
                        PaymentClient paymentClient) {
        this.orderRepository = orderRepository;
        this.pricingCalculator = pricingCalculator;
        this.paymentClient = paymentClient;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        // Build, price, persist, charge, notify, enqueue
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long id) {
        return orderRepository.findByIdWithLineItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
```

### Rules

- **One public method per use case.** `createOrder`, `cancelOrder`, `getOrder` — not `createOrderAndNotify`.
- **Own the transaction boundary.** `@Transactional` lives on service methods, not repositories or controllers.
- **Private methods for sub-steps** — only when extracting genuinely clarifies the flow. Don't extract a 3-line block just to name it.
- **Dependencies via constructor injection.** No `@Autowired` on fields. See `dependency-injection` skill for the full rules.
- **Domain exceptions** (e.g., `OrderNotFoundException`) can be nested in the service or live in `models/` if shared.

### Method signatures

- Accept request DTOs (records) or primitive IDs — not HTTP-specific types (`HttpServletRequest`, `ResponseEntity`)
- Return domain entities or result records — not response DTOs (that's the controller's job)
- Throw domain exceptions for error cases — let `@ControllerAdvice` map them to HTTP status codes

## Repository

**Responsibility**: Data access. Repositories define queries — they don't contain business logic.

### Structure

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.lineItems WHERE o.id = :id")
    Optional<Order> findByIdWithLineItems(@Param("id") Long id);

    List<Order> findByCustomerId(String customerId);

    List<Order> findByStatus(OrderStatus status);
}
```

### Rules

- **Extend `JpaRepository`** (or `CrudRepository`) — don't implement data access manually unless you need native SQL.
- **Method naming follows Spring Data conventions**: `findBy*`, `countBy*`, `existsBy*`, `deleteBy*`.
- **Use `@Query` for joins and complex queries** — derived query method names get unreadable past 2–3 conditions.
- **No business logic.** A repository returns data — it doesn't compute, filter by business rules, or transform results.
- **Fetch strategies in queries** — use `JOIN FETCH` in `@Query` to avoid N+1 problems rather than relying on entity-level `FetchType.EAGER`.

## Client

**Responsibility**: Wrap an external service boundary. The interface defines the contract; implementations handle the protocol (HTTP, SDK, etc.).

See `naming-conventions` for suffix rules and `integrating-external-sdk` / `integrating-external-api` for the full patterns. This section covers method design.

### Method design

```java
public interface PaymentClient {
    PaymentResult charge(String customerId, BigDecimal amount, String idempotencyKey);

    record PaymentResult(boolean success, String transactionId, String failureReason) {
        public static PaymentResult success(String transactionId) {
            return new PaymentResult(true, transactionId, null);
        }
        public static PaymentResult failure(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
```

### Rules

- **One method per operation.** `charge`, `refund`, `getStatus` — not `execute(String operation, Map<String, Object> params)`.
- **Method parameters are domain concepts** — `customerId`, `amount`, `idempotencyKey` — not SDK-specific types.
- **Return result records** with static factory methods for common outcomes (`success(...)`, `failure(...)`).
- **Nest request/response/exception types** in the interface when they're specific to this boundary.
- **Domain exceptions** for domain-level failures (e.g., `InsufficientStockException`). Let SDK/HTTP exceptions propagate for infrastructure failures — the service decides how to handle them.

## Pub/Sub

**Responsibility**: Define message contracts for asynchronous communication. Producers serialize and send; consumers deserialize and process.

### Payload records

Define payload types as records in the producing subdomain — they're part of that boundary's contract:

```java
// In the fulfillment package — produced by FulfillmentClient
public record FulfillmentPayload(long orderId, String customerId, int itemCount) {}

// In the notification package — produced by NotificationClient
public record OrderConfirmedEvent(String event, long orderId, String customerId, BigDecimal total) {}
```

### Producer rules

- **Serialize via `ObjectMapper`** — the orchestrating service serializes the payload, the client sends it.
- **Include a deduplication/idempotency key** for exactly-once semantics (SQS FIFO, SNS dedup).
- **Payload records are immutable** — use Java records, not mutable POJOs.
- **Keep payloads minimal** — include only what the consumer needs. Don't serialize entire entities.

### Consumer rules (when adding consumers)

- **Deserialize to the same record type** the producer uses — this validates the serialization contract.
- **One handler method per message type.** Don't multiplex on a `type` field inside a single handler.
- **Idempotent processing** — consumers must handle duplicate delivery gracefully.

