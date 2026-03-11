---
name: integrating-external-sdk
description: Integrates an external service that has a public SDK (e.g. AWS SQS, SNS, S3). Creates a test implementation of the SDK client so the real domain code runs in tests with SDK calls intercepted. Use when adding a new AWS service integration or any external service with a public SDK.
---

# Integrating an External Service via Public SDK

Use this pattern when the external service provides a **public, stable SDK** (e.g. AWS SDK, Stripe SDK, Twilio SDK). The key insight: test at the SDK client level. Your domain code runs unchanged in tests — only the underlying SDK calls are intercepted.

## Architecture

```
Your class              (uses SDK client, @Component, NO @Profile)
       |
SDK Client Interface    (e.g. SqsClient, SnsClient)
       |
   ┌───┴───┐
Real SDK    Test SDK Client (in-memory, records calls, validates inputs)
```

Your class depends on the SDK client via constructor injection. In production, Spring provides the real SDK client. In tests, `TestConfiguration` provides a test implementation that records calls and validates inputs.

## Step-by-step

### 1. Write your class that uses the SDK

**No `@Profile` annotation** — this class runs in both production and tests. The SDK client is injected.

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

### 2. Create the test SDK client

Implements the SDK client interface directly. Key responsibilities:
- **Configure known resources** (queues, topics, buckets) via constructor
- **Validate inputs** — throw realistic SDK exceptions for unknown resources
- **Record calls** — store raw SDK request objects for test assertion
- **Return realistic responses**
- **Simulate failures** — configurable exception rules via `throwWhen`

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
        // Check exception rules first — simulates runtime failures
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

    // Simulate failures: condition evaluates the request, supplier creates the exception
    public void throwWhen(Predicate<SendMessageRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptionRules.add(new ExceptionRule<>(condition, exception));
    }

    // Test assertions
    public List<SendMessageRequest> getSentMessages() { return Collections.unmodifiableList(sentMessages); }
    public SendMessageRequest getLastSentMessage() { return sentMessages.get(sentMessages.size() - 1); }
    public int getMessageCount() { return sentMessages.size(); }
    public void reset() { sentMessages.clear(); exceptionRules.clear(); }

    private record ExceptionRule<T>(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
```

### 3. Register in TestConfiguration

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

### 4. Wire into BaseIntegrationTest

```java
@Autowired
protected TestSqsClient testSqsClient;

@BeforeEach
void setUpBase() {
    testSqsClient.reset();
}
```

### 5. Assert on SDK-level recordings

```java
assertThat(testSqsClient.getMessageCount()).isEqualTo(1);
SendMessageRequest sqsRequest = testSqsClient.getLastSentMessage();
assertThat(sqsRequest.queueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/...");
assertThat(sqsRequest.messageBody()).contains("orderId");
```

### 6. Simulate SDK failures

Use `throwWhen` to test how your code handles SDK exceptions. The predicate evaluates each request; when it returns `true`, the supplied exception is thrown instead of processing the call.

```java
// Simulate a total service outage
testSqsClient.throwWhen(
        req -> true,
        () -> SqsException.builder().message("Service unavailable").build());

// Simulate failure only for a specific queue
testSqsClient.throwWhen(
        req -> req.queueUrl().contains("dead-letter"),
        () -> SqsException.builder().message("Access denied").build());
```

Exception rules are checked before known-resource validation, so they take priority. They're cleared on `reset()`.

For S3 (which has multiple operations), use `throwOnPut` and `throwOnGet`:

```java
testS3Client.throwOnPut(
        req -> req.key().endsWith(".pdf"),
        () -> S3Exception.builder().message("Write denied").build());
```

## Why this pattern

- Your real code is exercised in tests (catches serialization bugs, SDK usage errors)
- Tests assert on the SDK request objects — the actual contract with the external service
- No mocking frameworks needed
- Test client validates inputs just like the real service would

## Reference examples

See these files in the project for complete working examples:
- `src/test/java/.../support/aws/clients/TestSqsClient.java` — SQS test client
- `src/test/java/.../support/aws/clients/TestSnsClient.java` — SNS test client
- `src/test/java/.../support/aws/clients/TestS3Client.java` — S3 test client
- `src/main/java/.../fulfillment/SqsFulfillmentClient.java` — Uses SqsClient
- `src/main/java/.../notification/SnsNotificationClient.java` — Uses SnsClient
- `src/main/java/.../invoicing/S3DocumentClient.java` — Uses S3Client
- `src/test/java/.../support/TestConfiguration.java` — Test bean wiring
