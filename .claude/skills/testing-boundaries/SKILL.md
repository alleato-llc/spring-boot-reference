---
name: testing-boundaries
description: Creates test implementations that honor the contract at each external boundary. Covers SDK interfaces, SDK simulators (Mockito), and custom REST API interfaces. Use when adding, modifying, or reviewing test doubles for external dependencies.
version: 1.0.0
---

# Testing Boundaries

Every external dependency sits behind a contract boundary — an interface that defines what the dependency does, not how it does it (see `inversion-of-control`). This skill covers how to create test implementations that honor the contract, and how to verify your code uses the contract correctly.

## Shared principles

### 1. Fakes over mocks

Hand-written test implementations that conform to the real interface. No generic mock libraries for the test double itself — the test double is a real class with real behavior.

### 2. Contract fidelity

A test implementation must behave like the real implementation at the contract level:
- **Validate inputs** — throw realistic exceptions for invalid resources (unknown queue URLs, missing buckets)
- **Return realistic responses** — not empty objects or nulls
- **Respect the contract's error semantics** — throw the same exception types the real implementation would

A fake that always returns success without validation will let bugs through.

### 3. Call capture

Record every invocation so tests can assert on what was called:
- Store the raw request objects (SDK requests, method parameters)
- Provide accessor methods: `getLastSentMessage()`, `getReservationCount()`, `getReservations()`
- Return unmodifiable views of the recording list

### 4. Configurable errors via `throwWhen`

All test implementations support `throwWhen` — a predicate on the request that triggers a specific exception:

```java
testSqsClient.throwWhen(
        req -> req.queueUrl().contains("dead-letter"),
        () -> SqsException.builder().message("Access denied").build());
```

Exception rules are checked before normal processing and take priority over input validation.

### 5. Reset between tests

Every test implementation has a `reset()` method that clears:
- Recorded invocations
- Configured exception rules
- Any internal state (stored objects, counters)

`BaseIntegrationTest` calls `reset()` on all test implementations in `@BeforeEach`.

## Decision table

| Scenario | Pattern | Example |
|---|---|---|
| SDK provides a client interface | Pattern 1: Implement the interface | AWS SQS, SNS — `TestSqsClient implements SqsClient` |
| SDK client is a concrete class (no interface) | Pattern 2: Simulator with Mockito | S3Client — `S3Simulator` produces a mock |
| No SDK — you call a REST API | Pattern 3: Define your own interface | Inventory API — `InventoryClient` interface + `HttpInventoryClient` + `TestInventoryClient` |

## Pattern 1: SDK with interface

Use when the SDK provides a **public, stable interface** (e.g., AWS SDK v2 `SqsClient`, `SnsClient`). Your production code uses the SDK client via constructor injection. The test implementation implements the same interface.

### Architecture

```
Your class              (uses SDK client, @Component, NO @Profile)
       |
SDK Client Interface    (e.g. SqsClient, SnsClient)
       |
   ┌───┴───┐
Real SDK    Test implementation (in-memory, records calls, validates inputs)
```

### Step-by-step

**1. Write your class that uses the SDK** — no `@Profile`, the SDK client is injected:

```java
@Component
public class SqsFulfillmentClient implements FulfillmentClient {
    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsFulfillmentClient(SqsClient sqsClient,
                               @Value("${fulfillment.queue.url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void enqueue(String message, String deduplicationId) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message)
                .messageGroupId("order-fulfillment")
                .messageDeduplicationId(deduplicationId)
                .build());
    }
}
```

**2. Create the test implementation** — implements the SDK interface directly:

```java
public class TestSqsClient implements SqsClient {
    private final Set<String> knownQueueUrls;
    private final List<SendMessageRequest> sentMessages = new ArrayList<>();
    private final List<ExceptionRule<SendMessageRequest>> exceptionRules = new ArrayList<>();

    public TestSqsClient(Set<String> knownQueueUrls) {
        this.knownQueueUrls = knownQueueUrls;
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        for (var rule : exceptionRules) {
            if (rule.condition().test(request)) {
                throw rule.exception().get();
            }
        }
        if (!knownQueueUrls.contains(request.queueUrl())) {
            throw QueueDoesNotExistException.builder()
                    .message("The specified queue does not exist: " + request.queueUrl())
                    .build();
        }
        sentMessages.add(request);
        return SendMessageResponse.builder().messageId("test-msg-1").build();
    }

    @Override public String serviceName() { return "sqs"; }
    @Override public void close() {}

    public void throwWhen(Predicate<SendMessageRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptionRules.add(new ExceptionRule<>(condition, exception));
    }

    public List<SendMessageRequest> getSentMessages() { return Collections.unmodifiableList(sentMessages); }
    public SendMessageRequest getLastSentMessage() { return sentMessages.get(sentMessages.size() - 1); }
    public int getMessageCount() { return sentMessages.size(); }
    public void reset() { sentMessages.clear(); exceptionRules.clear(); }

    private record ExceptionRule<T>(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
```

**3. Register in TestConfiguration:**

```java
@Bean
public TestSqsClient testSqsClient() {
    return new TestSqsClient(Set.of(
            "https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo"));
}

@Bean
public SqsClient sqsClient(TestSqsClient testImpl) {
    return testImpl;
}
```

**4. Assert on recordings:**

```java
assertThat(testSqsClient.getMessageCount()).isEqualTo(1);
SendMessageRequest sqsRequest = testSqsClient.getLastSentMessage();
assertThat(sqsRequest.queueUrl()).contains("order-fulfillment");
assertThat(sqsRequest.messageBody()).contains("orderId");
```

## Pattern 2: SDK without interface (Simulator)

Use when the SDK client is a **concrete class with no interface** — you can't implement it directly. The simulator wraps a Mockito mock and provides a domain-language API for configuration and assertion.

### Architecture

```
Simulator                (owns mock + state + request log)
    |
    ├── simulate()       → produces the mock SDK client
    ├── domain API       → state queries + request log
    ├── throwWhen/On*    → configurable failure simulation
    └── reset()          → cleanup between tests
```

### Stateless vs stateful simulators

| Type | Examples | Primary API |
|---|---|---|
| **Stateless** | SQS (messages), SNS (publications) | Request list IS the history |
| **Stateful** | S3 (files), DynamoDB (items) | State queries + unified request log |

### Stateless simulator (SQS example)

```java
public class SqsSimulator {
    private final Set<String> knownQueueUrls;
    private final List<SendMessageRequest> requests = new ArrayList<>();
    private final ExpectedException<SendMessageRequest> exceptions = new ExpectedException<>();
    private SqsClient mock;

    public SqsSimulator(String... queueUrls) {
        this.knownQueueUrls = Set.of(queueUrls);
    }

    public SqsClient simulate() {
        if (mock == null) {
            mock = mock(SqsClient.class);
            when(mock.sendMessage(any(SendMessageRequest.class)))
                    .thenAnswer(inv -> handleSendMessage(inv.getArgument(0)));
        }
        return mock;
    }

    public List<SendMessageRequest> getMessages() { return Collections.unmodifiableList(requests); }
    public Optional<SendMessageRequest> findMessage(Predicate<SendMessageRequest> predicate) {
        return requests.stream().filter(predicate).findFirst();
    }
    public int messageCount() { return requests.size(); }

    public void throwWhen(Predicate<SendMessageRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptions.throwWhen(condition, exception);
    }

    public void reset() { requests.clear(); exceptions.reset(); }

    private SendMessageResponse handleSendMessage(SendMessageRequest request) {
        exceptions.checkRules(request);
        // validate queue URL...
        requests.add(request);
        return SendMessageResponse.builder().messageId("test-msg-1").build();
    }
}
```

### Stateful simulator (S3 example)

The primary API reflects current state. A unified request log preserves full SDK metadata for all operations.

```java
public class S3Simulator {
    private final Set<String> knownBuckets;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<S3Request> requests = new ArrayList<>();
    private final ExpectedException<S3Request> exceptions = new ExpectedException<>();

    public S3Simulator(String... buckets) { this.knownBuckets = Set.of(buckets); }

    public S3Client simulate() { /* mock PutObject, GetObject, wire to handlers */ }

    // State API
    public Optional<byte[]> findObject(String bucket, String key) { ... }
    public boolean hasObject(String bucket, String key) { ... }

    // Request log with pattern matching
    public List<S3Request> getRequests() { return Collections.unmodifiableList(requests); }
    public boolean hasRequest(Predicate<S3Request> predicate) { ... }

    // Per-operation failure simulation
    public void throwOnPut(Predicate<PutObjectRequest> condition, Supplier<? extends RuntimeException> exception) { ... }
    public void throwOnGet(Predicate<GetObjectRequest> condition, Supplier<? extends RuntimeException> exception) { ... }

    public void reset() { objects.clear(); requests.clear(); exceptions.reset(); }
}
```

### ExpectedException (shared infrastructure)

```java
public class ExpectedException<T> {
    private final List<ExceptionRule<T>> rules = new ArrayList<>();

    public void checkRules(T request) {
        for (var rule : rules) {
            if (rule.condition().test(request)) {
                throw rule.exception().get();
            }
        }
    }

    public void throwWhen(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {
        rules.add(new ExceptionRule<>(condition, exception));
    }

    public void reset() { rules.clear(); }

    private record ExceptionRule<T>(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
```

### Wiring simulators

```java
// TestConfiguration
@Bean
public SqsSimulator sqsSimulator() {
    return new SqsSimulator("https://sqs.us-east-1.amazonaws.com/...");
}

@Bean
public SqsClient sqsClient(SqsSimulator simulator) { return simulator.simulate(); }
```

## Pattern 3: Custom interface for REST APIs

Use when the external service has **no public SDK** — you define the interface, provide an HTTP implementation for production, and an in-memory test implementation.

### Architecture

```
Client Interface            (contract boundary you define)
       |
   ┌───┴───┐
HttpImpl        TestImpl
(@Profile       (in-memory, records calls,
 "!test")        configurable behavior)
```

### Step-by-step

**1. Define the interface** — include request/response records and domain exceptions as nested types:

```java
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

**2. Create the HTTP implementation** — `@Profile("!test")` so it's excluded in tests:

```java
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
}
```

**3. Create the test implementation** — records calls, supports `throwWhen`:

```java
public class TestInventoryClient implements InventoryClient {
    private final Set<String> outOfStockProducts = new HashSet<>();
    private final List<ReservationCall> reservations = new ArrayList<>();
    private final List<ExceptionRule> exceptionRules = new ArrayList<>();
    private int reservationCounter = 0;

    @Override
    public ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items) {
        var request = new ReservationRequest(orderId, List.copyOf(items));
        for (var rule : exceptionRules) {
            if (rule.condition().test(request)) { throw rule.exception().get(); }
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

    public void setOutOfStock(String productId) { outOfStockProducts.add(productId); }
    public void throwWhen(Predicate<ReservationRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptionRules.add(new ExceptionRule(condition, exception));
    }

    public List<ReservationCall> getReservations() { return Collections.unmodifiableList(reservations); }
    public int getReservationCount() { return reservations.size(); }
    public void reset() { reservations.clear(); outOfStockProducts.clear(); exceptionRules.clear(); reservationCounter = 0; }

    public record ReservationRequest(String orderId, List<ReservationItem> items) {}
    public record ReservationCall(String orderId, List<ReservationItem> items, String reservationId) {}
    private record ExceptionRule(Predicate<ReservationRequest> condition,
                                 Supplier<? extends RuntimeException> exception) {}
}
```

**4. Register and assert:**

```java
// TestConfiguration
@Bean
public TestInventoryClient testInventoryClient() { return new TestInventoryClient(); }

@Bean
public InventoryClient inventoryClient(TestInventoryClient testImpl) { return testImpl; }

// In tests
assertThat(testInventoryClient.getReservationCount()).isEqualTo(1);
ReservationCall reservation = testInventoryClient.getLastReservation();
assertThat(reservation.orderId()).isEqualTo(String.valueOf(order.id()));
```

### Key difference from Pattern 1

- HTTP impl uses `@Profile("!test")` — it does NOT run in tests
- Pattern 1: your code uses the real SDK class (no `@Profile`), only the SDK client is swapped
- Pattern 3: you own the contract boundary, so you can design it for testability

## Internal operation order

Each SDK call follows this sequence inside the test implementation:

1. **Check exception rules** — `throwWhen` predicates evaluated first
2. **Validate resource** — queue URL, topic ARN, bucket name. Throws realistic SDK exceptions for unknowns
3. **Update state** — (stateful only) e.g., store bytes in the object map
4. **Record** — only successful operations are recorded

## Wiring: TestConfiguration and BaseIntegrationTest

All test implementations are registered as Spring beans in `TestConfiguration`. Two beans per boundary: one for the test impl (with `Test*` type), one for the interface (returns the test impl).

```java
@Bean
public TestSqsClient testSqsClient() { return new TestSqsClient(Set.of("...")); }

@Bean
public SqsClient sqsClient(TestSqsClient testImpl) { return testImpl; }
```

`BaseIntegrationTest` autowires all test implementations and resets them in `@BeforeEach`:

```java
@Autowired protected TestSqsClient testSqsClient;
@Autowired protected TestInventoryClient testInventoryClient;

@BeforeEach
void setUpBase() {
    testSqsClient.reset();
    testInventoryClient.reset();
}
```

## Conventions

- Test implementations live in `support/clients/` (interface-level) or `support/aws/clients/` (SDK-level)
- Named `Test*` (e.g., `TestSqsClient`, `TestInventoryClient`)
- Simulators live in `support/aws/clients/simulators/`
- Every test implementation has a `reset()` method
- Record raw request objects — not projected/simplified versions
- Return unmodifiable views from accessor methods
- `throwWhen` predicates are checked before input validation
- Pattern 1 (SDK with interface): no `@Profile` on your class — the SDK client bean is swapped
- Pattern 3 (custom interface): `@Profile("!test")` on the HTTP implementation

## Checklist

When creating or reviewing test implementations, verify:

- [ ] Test implementation honors the contract — validates inputs, returns realistic responses
- [ ] All invocations are recorded with full request details
- [ ] `throwWhen` support for configurable failure simulation
- [ ] `reset()` clears recordings, exception rules, and internal state
- [ ] Registered in `TestConfiguration` with two `@Bean` methods
- [ ] Wired into `BaseIntegrationTest` with `@Autowired` and `@BeforeEach` reset
- [ ] Assertions use recorded request objects — not string matching
- [ ] Pattern 3: HTTP impl has `@Profile("!test")`
