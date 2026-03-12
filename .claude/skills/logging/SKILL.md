---
name: logging
description: Logging standards for Spring Boot applications. SLF4J + Logback, structured JSON output, @Redacted annotation for sensitive field protection, log level guidelines, where to log.
version: 1.0.0
---

# Logging

## Rules

- SLF4J is the logging API; Logback is the implementation (Spring Boot default — do not replace)
- Never add a competing logging implementation (Log4j2, JUL direct usage). If a library brings one transitively, exclude it.
- Use `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` — no inheritance, no injection
- Log at boundaries (incoming requests, outgoing calls, errors), not inside business logic
- Never interpolate sensitive values into log messages or exception messages
- All domain objects that may contain sensitive data must use the `RedactingToStringBuilder` for `toString()`
- Structured logging (JSON) in all environments — human-readable format only for local development if needed
- Trace context (`traceId`, `spanId`) appears in log output via MDC — see **tracing** skill for propagation

## Package layout

- `@Redacted` and `RedactingToStringBuilder` live in `{org}.logging` — org-level infrastructure, outside any project or domain package

## Log levels

| Level | When | Examples |
|---|---|---|
| ERROR | Failure requiring attention — something is broken | Unhandled exceptions, external service down, data corruption |
| WARN | Degraded but recoverable — worth investigating if frequent | Retry succeeded, fallback used, deprecated API called |
| INFO | Key workflow events — the "happy path audit trail" | Order created, payment charged, fulfillment enqueued |
| DEBUG | Diagnostics — off in production by default | Request/response details, cache hits/misses, SQL parameters |

Rules:
- ERROR must be actionable — if no one needs to do anything, it's not an error
- INFO should let you reconstruct what happened for a request without reading code
- DEBUG is free to be verbose — it's off in production
- Never log at WARN or ERROR in a loop — aggregate first

## Sensitive data protection

### @Redacted annotation

Fields annotated with `@Redacted` are replaced with `***` in `toString()` output. This prevents accidental logging of sensitive data.

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Redacted {}
```

### Usage on domain objects

```java
public class PaymentRequest {
    private String customerId;
    private BigDecimal amount;
    @Redacted
    private String cardNumber;
    @Redacted
    private String cvv;

    @Override
    public String toString() {
        return RedactingToStringBuilder.toString(this);
    }
}
```

`log.info("Processing payment: {}", paymentRequest)` produces:
```
Processing payment: PaymentRequest{customerId=cust-123, amount=99.99, cardNumber=***, cvv=***}
```

### RedactingToStringBuilder

Reflection-based utility that reads `@Redacted` and replaces field values:

```java
public final class RedactingToStringBuilder {

    private RedactingToStringBuilder() {}

    public static String toString(Object obj) {
        if (obj == null) return "null";
        var clazz = obj.getClass();
        var fields = clazz.getDeclaredFields();
        var sb = new StringBuilder(clazz.getSimpleName()).append('{');
        boolean first = true;
        for (var field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!first) sb.append(", ");
            first = false;
            field.setAccessible(true);
            sb.append(field.getName()).append('=');
            try {
                sb.append(field.isAnnotationPresent(Redacted.class) ? "***" : field.get(obj));
            } catch (IllegalAccessException e) {
                sb.append("<inaccessible>");
            }
        }
        return sb.append('}').toString();
    }
}
```

### What @Redacted does NOT catch

`@Redacted` protects `toString()`. It cannot protect against:

```java
// BAD — string interpolation bypasses @Redacted entirely
throw new {ProjectName}IllegalArgumentException("Bad password: " + user.getPassword());
log.info("Card: {}", request.getCardNumber());
```

This is a discipline problem, not an infrastructure problem. Rules:
- Never call getters on `@Redacted` fields in log statements or exception messages
- Log the *object*, not individual fields, when sensitive data is involved
- Code review is the enforcement mechanism for this class of leak

## Logging framework

### SLF4J + Logback

Spring Boot ships SLF4J + Logback and routes JUL, commons-logging, and log4j through SLF4J automatically.

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyService {
    private static final Logger log = LoggerFactory.getLogger(MyService.class);
}
```

Do not:
- Use `@Slf4j` (Lombok) — explicit declaration is clearer and has no annotation processor dependency
- Use `java.util.logging` directly
- Add `log4j-core` or any alternative backend

### External library logging

Spring Boot's `spring-boot-starter-logging` (included via `spring-boot-starter-web`) already bridges JUL, commons-logging, and log4j-api to SLF4J. If a transitive dependency brings a conflicting logging backend, exclude it:

```groovy
implementation('some-library:some-artifact') {
    exclude group: 'org.apache.logging.log4j', module: 'log4j-core'
}
```

## Structured logging

### JSON output

Use Logback's structured logging support for machine-readable output. The JSON format includes timestamp, level, logger, message, MDC (including traceId/spanId), and any key-value pairs.

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.classic.spi.LoggingEventJSONEncoder">
        <!-- Spring Boot 3.4+ built-in structured logging -->
    </appender>

    <!-- Or use logstash-logback-encoder for richer control -->
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
    </appender>
</configuration>
```

Alternatively, Spring Boot 3.4+ supports structured logging via application config:

```yaml
logging:
  structured:
    format:
      console: logstash  # or ecs
```

## Where to log

### Log at boundaries

```java
// Incoming request (controller or filter level)
log.info("Creating order for customer={}", request.customerId());

// Outgoing external call
log.info("Charging payment amount={} idempotencyKey={}", amount, idempotencyKey);

// Workflow result
log.info("Order created orderId={} status={}", order.getId(), order.getStatus());
```

### Do not log inside business logic

```java
// BAD — logging inside a calculator
public PricingResult calculate(List<LineItem> items, String promoCode) {
    log.debug("Calculating price for {} items", items.size());  // noise
    // ...
}

// GOOD — the caller logs the result at the boundary
var pricing = pricingCalculator.calculate(order.getLineItems(), promoCode);
log.debug("Pricing calculated subtotal={} discount={} total={}", pricing.subtotal(), pricing.discount(), pricing.total());
```

### Error logging

Error logging belongs in `@ControllerAdvice` and infrastructure — not in services:

```java
// GlobalExceptionHandler already logs 5xx errors
@ExceptionHandler({ProjectName}InternalServerException.class)
public ResponseEntity<ErrorResponse> handleInternalServer({ProjectName}InternalServerException ex) {
    log.error("Internal error: {}", ex.getMessage(), ex);  // log here, not in service
    // ...
}
```

Do not double-log: if `@ControllerAdvice` logs the error, the service should not also log it.

## Testing

Logging is infrastructure — do not assert on log output in tests. Instead:
- Assert on observable behavior (HTTP responses, DB state, SDK recordings)
- If a log message is important enough to test, the information it carries should also be observable through a side effect
- For structured logging format validation, a dedicated infrastructure test against the Logback config is sufficient
