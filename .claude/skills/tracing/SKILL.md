---
name: tracing
description: Distributed tracing for Spring Boot applications. Micrometer Tracing setup, correlation ID lifecycle, HTTP auto-propagation, SQS/SNS message attribute propagation, consumer MDC extraction. Use when adding distributed tracing, propagating correlation IDs, or instrumenting cross-service calls.
version: 1.0.0
---

# Tracing

## Rules

- Every request gets a `traceId` (identifies the full request chain) and `spanId` (identifies a single unit of work within that chain)
- Micrometer Tracing with OpenTelemetry bridge generates and manages trace context
- HTTP propagation is automatic (Spring auto-configures `RestClient`/`RestTemplate` interceptors)
- Message propagation (SQS, SNS) uses message attributes — not the message body
- Attribute keys: `traceId` and `spanId` (simple keys, not W3C `traceparent` format — message attributes are string maps, not HTTP headers)
- Consumers must extract trace context from message attributes and set MDC before processing
- Trace context is included in structured log output via MDC — see **logging** skill for log format

## Package layout

- `TraceAttributes` and `TraceContext` live in `{org}.tracing` — org-level infrastructure, outside any project or domain package

## Concepts

- **traceId** — identifies the entire request chain end-to-end. One user action (e.g., "create order") gets one traceId that follows it through your service, into SQS, into the fulfillment consumer, into whatever that consumer calls. Every log line for that action shares the same traceId.
- **spanId** — identifies a single unit of work within a trace. The controller handling the request is one span, the payment client call is another, the SQS publish is another. Spans nest into a tree showing what happened and how long each step took.

## Setup

### Dependencies

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
```

### Configuration

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # Sample all requests (adjust for production)
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

## Propagation

### HTTP — automatic

Spring auto-configures trace propagation for `RestClient` and `RestTemplate`. No code needed — `traceId` and `spanId` are forwarded in HTTP headers on outgoing calls and extracted from headers on incoming calls.

### SQS/SNS — message attributes

Trace context travels as message attributes, not in the message body. The message body is the domain payload (`FulfillmentPayload`, `OrderConfirmedEvent`); trace context is transport metadata.

Why attributes over body:
- **Separation of concerns** — domain payload stays clean; trace context is infrastructure
- **Consumer flexibility** — extract traceId and set MDC *before* deserializing the body, so deserialization errors are correlated
- **Filtering/routing** — SQS/SNS can filter on attributes without parsing the body

#### Publishing

Clients that publish to SQS/SNS include trace context in message attributes:

```java
// In a client that publishes to SQS
public void enqueue(String messageBody, String deduplicationId) {
    var attributesBuilder = ImmutableMap.<String, MessageAttributeValue>builder();

    // Attach trace context from MDC
    var traceId = MDC.get("traceId");
    if (traceId != null) {
        attributesBuilder.put("traceId", MessageAttributeValue.builder()
                .dataType("String").stringValue(traceId).build());
    }
    var spanId = MDC.get("spanId");
    if (spanId != null) {
        attributesBuilder.put("spanId", MessageAttributeValue.builder()
                .dataType("String").stringValue(spanId).build());
    }

    sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(messageBody)
            .messageAttributes(attributesBuilder.build())
            .messageGroupId(groupId)
            .messageDeduplicationId(deduplicationId)
            .build());
}
```

For SNS:

```java
public void publish(String topicArn, String message) {
    var attributesBuilder = ImmutableMap.<String, MessageAttributeValue>builder();

    var traceId = MDC.get("traceId");
    if (traceId != null) {
        attributesBuilder.put("traceId", MessageAttributeValue.builder()
                .dataType("String").stringValue(traceId).build());
    }

    snsClient.publish(PublishRequest.builder()
            .topicArn(topicArn)
            .message(message)
            .messageAttributes(attributesBuilder.build())
            .build());
}
```

#### Extracting a utility

If multiple clients publish with trace context, extract the attribute-building into a shared utility:

```java
public final class TraceAttributes {

    private TraceAttributes() {}

    public static Map<String, MessageAttributeValue> sqsAttributes() {
        var builder = new HashMap<String, MessageAttributeValue>();
        addIfPresent(builder, "traceId");
        addIfPresent(builder, "spanId");
        return Map.copyOf(builder);
    }

    private static void addIfPresent(Map<String, MessageAttributeValue> map, String key) {
        var value = MDC.get(key);
        if (value != null) {
            map.put(key, MessageAttributeValue.builder()
                    .dataType("String").stringValue(value).build());
        }
    }
}
```

Usage:

```java
sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(queueUrl)
        .messageBody(messageBody)
        .messageAttributes(TraceAttributes.sqsAttributes())
        .messageGroupId(groupId)
        .messageDeduplicationId(deduplicationId)
        .build());
```

### Consuming — MDC extraction

Downstream consumers extract trace context from message attributes and set MDC so their logs correlate back to the originating request. Use `TraceContext.run()` to ensure MDC is always cleaned up:

```java
public final class TraceContext {

    private TraceContext() {}

    public static void run(Map<String, MessageAttributeValue> attributes, Runnable action) {
        setFromAttribute(attributes, "traceId");
        setFromAttribute(attributes, "spanId");
        try {
            action.run();
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    private static void setFromAttribute(Map<String, MessageAttributeValue> attributes, String key) {
        var attr = attributes.get(key);
        if (attr != null && attr.stringValue() != null) {
            MDC.put(key, attr.stringValue());
        }
    }
}
```

Usage:

```java
public void handleMessage(Message message) {
    TraceContext.run(message.messageAttributes(), () -> {
        var payload = objectMapper.readValue(message.body(), FulfillmentPayload.class);
        // ... process
    });
}
```

Key rules for consumers:
- Set MDC **before** any processing or deserialization — errors during deserialization should be traceable
- **Always clean up MDC** — `TraceContext.run()` handles this via `finally`; without cleanup, MDC is thread-local and leaks to the next message processed on the same thread
- If no trace attributes are present (e.g., message from a system that doesn't propagate), processing continues without correlation — do not fail

### Virtual threads and async propagation

MDC uses `ThreadLocal`, which works correctly on virtual threads — each virtual thread gets its own `ThreadLocal` storage. The `TraceContext.run()` pattern works as-is for synchronous message handlers running on virtual threads.

The problem arises when a handler **spawns child virtual threads** (e.g., via `Executors.newVirtualThreadPerTaskExecutor()`). Child threads do not inherit `ThreadLocal` values, so trace context is lost.

For async propagation, add Micrometer's context-propagation library:

```groovy
implementation 'io.micrometer:context-propagation'
```

This library hooks into `ThreadLocal` and automatically copies MDC values when tasks are submitted to executors, including virtual thread executors. Spring Boot 3.x auto-configures this when the dependency is present.

Until async message handling is needed, the synchronous `TraceContext.run()` pattern is sufficient.

## Testing

### Verifying attribute propagation

Integration tests verify that published messages carry trace attributes by asserting on the recorded SDK calls:

```java
// After creating an order (which publishes to SQS)
var sqsRequest = testSqsClient.getLastSentMessage();
assertThat(sqsRequest.messageAttributes()).containsKey("traceId");
assertThat(sqsRequest.messageAttributes().get("traceId").stringValue()).isNotBlank();
```

### Test doubles

Test SDK clients (e.g., `TestSqsClient`) already record full request objects including message attributes — no special test infrastructure needed for verifying propagation.
