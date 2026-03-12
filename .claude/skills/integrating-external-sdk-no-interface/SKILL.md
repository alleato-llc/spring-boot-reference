---
name: integrating-external-sdk-no-interface
description: Creates a simulator for an external SDK when the SDK client is a concrete class (no interface to implement). The simulator produces a mock client, tracks invocations, and exposes a domain-language API for test assertions. Use when integrating an SDK that doesn't provide a client interface.
---

# Integrating an External SDK (No Interface)

Use this pattern when the external service provides an SDK, but the SDK client is a **concrete class with no interface** — you can't implement the interface directly as a test double. The simulator pattern wraps the mock client and provides a domain-language API for configuration, state inspection, and assertions.

> **Note:** The examples below use AWS SDK clients for illustration. The mock is created with Mockito (`mock(ClientClass.class)`) and stubbed with `thenAnswer` to delegate to handler methods. This works for both concrete classes and interfaces — the rest of the pattern is identical either way. Unstubbed methods return Mockito defaults (null, 0, false); any accidental call to an unhandled method surfaces as a NullPointerException in the test.

## Architecture

```
Simulator                (owns mock + state + request log)
    |
    ├── simulate()       → produces the mock SDK client
    ├── domain API       → state queries + request log
    ├── throwWhen/On*    → configurable failure simulation
    └── reset()          → cleanup between tests

ExpectedException<T>     (generic infrastructure)
    ├── checkRules()     → evaluate exception rules against a request
    ├── throwWhen()      → register exception rules
    └── reset()          → clear rules
```

Your production class depends on the SDK client via constructor injection. In tests, `TestConfiguration` provides the simulator's mock client via `@Bean`.

## Stateless vs stateful simulators

| Type | Examples | Primary API | Request tracking |
|------|----------|-------------|-----------------|
| **Stateless** | SQS (messages), SNS (publications) | Direct request list IS the history | `List<RequestType>` |
| **Stateful** | S3 (files), DynamoDB (items) | State queries (what exists now) + unified request log | `List<BaseRequestType>` with pattern matching |

## Step-by-step

### 1. Create ExpectedException (shared infrastructure)

Handles only exception rule checking. Simulators compose one or more instances internally.

```java
// src/test/java/.../support/aws/clients/simulators/ExpectedException.java
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

### 2. Create the simulator

#### Stateless simulator (SQS example — fire-and-forget)

The domain API (`getMessages`, `findMessage`) returns the SDK request objects directly — no lossy projection. The request list IS the complete history.

```java
// src/test/java/.../support/aws/clients/simulators/SqsSimulator.java
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

    // Domain API — returns SDK request objects directly
    public List<SendMessageRequest> getMessages() {
        return Collections.unmodifiableList(requests);
    }

    public Optional<SendMessageRequest> findMessage(Predicate<SendMessageRequest> predicate) {
        return requests.stream().filter(predicate).findFirst();
    }

    public int messageCount() { return requests.size(); }

    // Failure simulation
    public void throwWhen(Predicate<SendMessageRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptions.throwWhen(condition, exception);
    }

    public void reset() { requests.clear(); exceptions.reset(); }

    private SendMessageResponse handleSendMessage(SendMessageRequest request) {
        exceptions.checkRules(request);   // 1. Check exception rules
        // validate queue URL...           // 2. Validate resource
        requests.add(request);             // 3. Record successful call
        return SendMessageResponse.builder().messageId("test-msg-1").build();
    }
}
```

#### Stateful simulator (S3 example — persistent store)

The primary API reflects current state (`findObject`, `hasObject`). A unified `List<S3Request>` preserves full SDK metadata for all operations. Use pattern matching to query by operation type.

```java
// src/test/java/.../support/aws/clients/simulators/S3Simulator.java
public class S3Simulator {
    private final Set<String> knownBuckets;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<S3Request> requests = new ArrayList<>();
    private final ExpectedException<S3Request> exceptions = new ExpectedException<>();

    public S3Simulator(String... buckets) {
        this.knownBuckets = Set.of(buckets);
    }

    public S3Client simulate() {
        if (mock == null) {
            mock = mock(S3Client.class);
            when(mock.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenAnswer(inv -> handlePut(inv.getArgument(0), inv.getArgument(1)));
            when(mock.getObject(any(GetObjectRequest.class)))
                    .thenAnswer(inv -> handleGet(inv.getArgument(0)));
        }
        return mock;
    }

    // State API — what exists now
    public Optional<byte[]> findObject(String bucket, String key) {
        return Optional.ofNullable(objects.get(bucket + "/" + key));
    }
    public boolean hasObject(String bucket, String key) { ... }
    public int objectCount() { return objects.size(); }

    // Request log — full SDK metadata, use pattern matching to query
    public List<S3Request> getRequests() { return Collections.unmodifiableList(requests); }
    public boolean hasRequest(Predicate<S3Request> predicate) {
        return requests.stream().anyMatch(predicate);
    }

    // Failure simulation — typed API, unified internally via pattern matching
    public void throwOnPut(Predicate<PutObjectRequest> condition,
                           Supplier<? extends RuntimeException> exception) {
        exceptions.throwWhen(
                r -> r instanceof PutObjectRequest put && condition.test(put),
                exception);
    }
    public void throwOnGet(Predicate<GetObjectRequest> condition,
                           Supplier<? extends RuntimeException> exception) {
        exceptions.throwWhen(
                r -> r instanceof GetObjectRequest get && condition.test(get),
                exception);
    }

    public void reset() {
        objects.clear(); requests.clear(); exceptions.reset();
    }

    private PutObjectResponse handlePut(PutObjectRequest request, RequestBody requestBody) {
        exceptions.checkRules(request);     // 1. Check exception rules
        // validate bucket...                // 2. Validate resource
        // store bytes in objects map         // 3. Update state
        requests.add(request);               // 4. Record (full SDK metadata preserved)
        return PutObjectResponse.builder().build();
    }
}
```

### 3. Register in TestConfiguration

```java
@Bean
public SqsSimulator sqsSimulator() {
    return new SqsSimulator("https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo");
}

@Bean
public SqsClient sqsClient(SqsSimulator simulator) {
    return simulator.simulate();
}

@Bean
public S3Simulator s3Simulator() {
    return new S3Simulator("order-invoices");
}

@Bean
public S3Client s3Client(S3Simulator simulator) {
    return simulator.simulate();
}
```

### 4. Wire into BaseIntegrationTest

```java
@Autowired
protected SqsSimulator sqsSimulator;

@Autowired
protected S3Simulator s3Simulator;

@BeforeEach
void setUpBase() {
    sqsSimulator.reset();
    s3Simulator.reset();
}
```

### 5. Assert with the domain API

```java
// Stateless — SDK request objects directly, full metadata preserved
assertThat(sqsSimulator.messageCount()).isEqualTo(1);
assertThat(sqsSimulator.findMessage(m -> m.messageBody().contains("order-123"))).isPresent();

// Stateful — assert on state
assertThat(s3Simulator.hasObject("order-invoices", "invoices/order-123.pdf")).isTrue();
assertThat(s3Simulator.findObject("order-invoices", "invoices/order-123.pdf")).isPresent();

// Stateful — request log with pattern matching (full SDK metadata)
assertThat(s3Simulator.hasRequest(r -> r instanceof PutObjectRequest put
        && put.key().endsWith(".pdf")
        && put.contentType().equals("application/pdf"))).isTrue();
```

### 6. Simulate failures

```java
// Stateless — single throwWhen
sqsSimulator.throwWhen(
        req -> true,
        () -> SqsException.builder().message("Service unavailable").build());

// Stateful — per-operation
s3Simulator.throwOnPut(
        req -> req.key().endsWith(".pdf"),
        () -> S3Exception.builder().message("Write denied").build());

s3Simulator.throwOnGet(
        req -> req.key().endsWith(".pdf"),
        () -> S3Exception.builder().message("Access denied").build());
```

## Internal operation order

Each SDK call follows this sequence inside the simulator:

1. **Check exception rules** — `exceptions.checkRules(request)`. Throws if matched.
2. **Validate resource** — queue URL, topic ARN, bucket name. Throws realistic SDK exceptions for unknowns.
3. **Update state** — (stateful only) e.g., store bytes in the object map.
4. **Record** — `requests.add(request)`. Only successful operations are recorded.

## When to use this vs other patterns

| Scenario | Pattern |
|----------|---------|
| SDK provides an interface (AWS SDK v2) | `integrating-external-sdk` (simpler — implement interface directly) |
| SDK client is a concrete class, you want SDK-level testing | **This pattern** |
| No SDK, custom REST API | `integrating-external-api` (define your own interface) |

