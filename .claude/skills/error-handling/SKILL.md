---
name: error-handling
description: Error handling patterns for Spring Boot applications. Minimal exception hierarchy (abstract base + 4 subclasses), centralized @ControllerAdvice maps exception type to HTTP status, context maps for structured error details. Use when adding exception types, defining error responses, or reviewing error mapping.
version: 1.0.0
---

# Error Handling

## Rules

- No checked exceptions
- Minimal hierarchy: abstract base `{ProjectName}Exception` + 4 subclasses
- No bare `RuntimeException` — always use a `{ProjectName}` exception subclass
- Exceptions describe *what went wrong*, not the HTTP response — no `HttpStatus` on exceptions
- `@ControllerAdvice` maps exception type to HTTP status centrally
- Server errors: log details, return generic message (never leak internals)
- Client errors: include message and context in response
- Boundary exceptions (e.g., `InsufficientStockException` on client interfaces) are caught by the service and converted to a `{ProjectName}` exception subclass — external exception types must never propagate past the service layer

## Package layout

- Exception classes live in an `exception/` subpackage of the domain root (e.g., `ordering/exception/`)
- `GlobalExceptionHandler` lives in `controller/exception/`
- Response records (`ErrorResponse`, `ValidationErrorResponse`) live in `controller/response/`

## Exception hierarchy

| Exception | Purpose | HTTP Status (via ControllerAdvice) |
|---|---|---|
| `{ProjectName}Exception` | Abstract base — carries message + context map | N/A (never thrown directly) |
| `{ProjectName}IllegalArgumentException` | Client sent bad input | 400 |
| `{ProjectName}NotFoundException` | Resource not found | 404 |
| `{ProjectName}InternalServerException` | Known internal failure (e.g., serialization) | 500 |
| `{ProjectName}RuntimeException` | Unexpected/unhandled | 500 |

Why this design:
- **Abstract base** — forces callers to pick the right subclass; no ambiguous "generic" throws
- **Exception type determines HTTP status** — `@ControllerAdvice` maps each subclass, so exceptions stay decoupled from HTTP concerns
- **Context map on base** — replaces the need for deeper hierarchies (e.g., `Map.of("productId", id)` for insufficient stock)
- **4 subclasses cover all cases** — bad input, not found, known internal failure, unexpected failure

## {ProjectName}Exception design

Abstract base with message (via super) and context map. Protected constructors for subclasses.

```java
// For this project: OrderingException
public abstract class OrderingException extends RuntimeException {
    private final Map<String, Object> context;

    protected OrderingException(String message, Map<String, Object> context, Throwable cause) {
        super(message, cause);
        this.context = context;
    }

    protected OrderingException(String message, Map<String, Object> context) {
        this(message, context, null);
    }

    protected OrderingException(String message, Throwable cause) {
        this(message, Map.of(), cause);
    }

    protected OrderingException(String message) {
        this(message, Map.of(), null);
    }

    public Map<String, Object> getContext() { return context; }
}
```

## Subclasses

Each subclass is minimal — constructors delegate to the base.

```java
// Client sent bad input → 400
public class OrderingIllegalArgumentException extends OrderingException {
    public OrderingIllegalArgumentException(String message) { super(message); }
    public OrderingIllegalArgumentException(String message, Map<String, Object> context) { super(message, context); }
}

// Resource not found → 404
public class OrderingNotFoundException extends OrderingException {
    public OrderingNotFoundException(String message) { super(message); }
}

// Known internal failure → 500
public class OrderingInternalServerException extends OrderingException {
    public OrderingInternalServerException(String message, Throwable cause) { super(message, cause); }
}

// Unexpected/unhandled → 500
public class OrderingRuntimeException extends OrderingException {
    public OrderingRuntimeException(String message, Throwable cause) { super(message, cause); }
}
```

## GlobalExceptionHandler

Centralized `@ControllerAdvice` with one `@ExceptionHandler` per subclass:

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderingIllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(OrderingIllegalArgumentException ex) {
        var body = new ErrorResponse(ex.getMessage(), 400, ex.getContext());
        return ResponseEntity.status(400).body(body);
    }

    @ExceptionHandler(OrderingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderingNotFoundException ex) {
        var body = new ErrorResponse(ex.getMessage(), 404, Map.of());
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(OrderingInternalServerException.class)
    public ResponseEntity<ErrorResponse> handleInternalServer(OrderingInternalServerException ex) {
        log.error("Internal error: {}", ex.getMessage(), ex);
        var body = new ErrorResponse("An internal error occurred", 500, Map.of());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(OrderingRuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(OrderingRuntimeException ex) {
        log.error("Internal error: {}", ex.getMessage(), ex);
        var body = new ErrorResponse("An internal error occurred", 500, Map.of());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationException(...) { ... }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("An internal error occurred", 500, Map.of()));
    }
}
```

## Error response format

```java
// For {ProjectName} exceptions and catch-all
record ErrorResponse(String message, int status, Map<String, Object> context) {}

// For @Valid failures
record ValidationErrorResponse(String message, int status, List<FieldError> errors) {
    record FieldError(String field, String error) {}
}
```

## Service layer patterns

### Throwing specific exceptions

```java
// Not found
return orderRepository.findByIdWithLineItems(id)
        .orElseThrow(() -> new OrderingNotFoundException("Order not found: " + id));

// Serialization failure
try {
    return objectMapper.writeValueAsString(payload);
} catch (JsonProcessingException e) {
    throw new OrderingInternalServerException("Failed to serialize payload", e);
}
```

### Catching boundary exceptions

Boundary exceptions (defined on client interfaces) are caught and converted to the appropriate subclass:

```java
try {
    var confirmation = inventoryClient.reserveItems(orderId, items);
    order.withInventoryReservationId(confirmation.reservationId());
} catch (InsufficientStockException e) {
    throw new OrderingIllegalArgumentException(e.getMessage(), Map.of("productId", e.getProductId()));
}
```

## Testing

Assert on `ApiException` status and deserialized `ErrorResponse` body:

```java
assertThatThrownBy(() -> orderClient.getOrder(nonExistentId))
    .isInstanceOf(ApiException.class)
    .satisfies(ex -> {
        var apiEx = (ApiException) ex;
        assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
        var error = deserialize(apiEx.body(), ErrorResponse.class);
        assertThat(error.message()).contains("Order not found: " + nonExistentId);
        assertThat(error.status()).isEqualTo(404);
    });
```

For 500 errors, verify the response body is generic (no internal details leaked):

```java
var error = deserialize(apiEx.body(), ErrorResponse.class);
assertThat(error.message()).isEqualTo("An internal error occurred");
assertThat(error.status()).isEqualTo(500);
```

For client errors with context:

```java
var error = deserialize(apiEx.body(), ErrorResponse.class);
assertThat(error.message()).contains("Insufficient stock");
assertThat(error.context()).containsEntry("productId", outOfStockProduct);
```
