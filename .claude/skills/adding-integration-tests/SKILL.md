---
name: adding-integration-tests
description: Adds integration tests for Spring Boot API endpoints. Tests boot the full app with Postgres (via docker-compose), exercise the API via HTTP, and assert on observable side effects (response, DB state, SDK call recordings). Use when writing integration tests or testing API endpoints.
---

# Adding Integration Tests

Integration tests verify the full request lifecycle: HTTP request -> controller -> service -> database + external services -> HTTP response.

## Structure

- Test class extends `BaseIntegrationTest`
- Named `*IntegrationTest` (e.g., `OrderApiIntegrationTest`)
- Lives in the **same package** as the class under test
- Uses a typed HTTP client (e.g., `OrderClient`) for API calls

## Typed test HTTP client

Each API should have a typed client that encapsulates HTTP details and provides a clean interface for tests. The client follows these conventions:

**Default status assertion.** Methods without a status parameter assert the expected success status (e.g., 201 for create, 200 for get). An overloaded variant accepts an explicit status when the test expects something different.

**Error responses throw exceptions.** On error status codes, the client throws a custom exception carrying the status and response body. Tests assert on error responses using `assertThatThrownBy`.

**Register as a Spring bean.** The client and its `RestClient` are defined in `TestConfiguration` and injected via `BaseIntegrationTest`.

```java
public class MyApiClient {

    private final RestClient restClient;

    public MyApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    // Default: asserts expected success status, returns typed response
    public MyResponse createThing(MyRequest request) {
        return createThing(request, HttpStatus.CREATED);
    }

    // Explicit status: asserts the given status
    public MyResponse createThing(MyRequest request, HttpStatus expectedStatus) {
        return restClient.post()
                .uri("/api/things")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((req, res) -> {
                    HttpStatusCode status = res.getStatusCode();
                    if (status.isError()) {
                        String body = new String(res.getBody().readAllBytes());
                        throw new ApiException(HttpStatus.valueOf(status.value()), body);
                    }
                    assertThat(status).isEqualTo(expectedStatus);
                    return res.bodyTo(MyResponse.class);
                });
    }

    // Exception for error responses — define per client or share across clients
    public static class ApiException extends RuntimeException {
        private final HttpStatus status;
        private final String body;

        public ApiException(HttpStatus status, String body) {
            super("API returned %d: %s".formatted(status.value(), body));
            this.status = status;
            this.body = body;
        }

        public HttpStatus status() { return status; }
        public String body() { return body; }
    }
}
```

Tests then read naturally:

```java
// Success — status is asserted automatically
MyResponse response = myClient.createThing(request);
assertThat(response.name()).isEqualTo("expected");

// Error — assert on the exception
assertThatThrownBy(() -> myClient.createThing(badRequest))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> {
            var apiEx = (ApiException) ex;
            assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(apiEx.body()).contains("Thing not found");
        });
```

## Template

```java
package com.alleato.ecommerce.ordering.controller;

import com.alleato.ecommerce.ordering.models.*;
import com.alleato.ecommerce.ordering.support.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyFeatureIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("POST /api/endpoint — success")
    class SuccessCase {

        @Test
        void returnsExpectedResponse() {
            // Arrange: build request using domain models
            // Act: call API via typed client — status asserted automatically
            // Assert: verify response body, side effects
        }
    }

    @Nested
    @DisplayName("POST /api/endpoint — failure scenario")
    class FailureCase {

        @Test
        void handlesErrorGracefully() {
            // Configure test doubles for failure
            // testPaymentClient.willFail("reason");

            // Assert error response via exception
            // assertThatThrownBy(() -> client.createThing(request))
            //         .isInstanceOf(ApiException.class)
            //         .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
}
```

## What to assert on

Assert on **observable side effects**, not internal implementation:

1. **HTTP response** — response body fields (status is asserted by the client)
2. **Database state** — fetch via API and verify persisted values
3. **SDK call recordings** — verify external service calls happened with correct data
   - `testSqsClient.getLastSentMessage()` — SQS messages
   - `testSnsClient.getLastPublishedMessage()` — SNS notifications
   - `testS3Client.getLastPutRequest()` — S3 document storage
   - `testPaymentClient.getLastInvocation()` — payment charges
   - `testInventoryClient.getLastReservation()` — inventory reservations
4. **Negative assertions** — verify side effects did NOT happen on failure paths
   - `assertThat(testSnsClient.getMessageCount()).isZero()`

## How to write assertions

### Derive expected values from inputs

Do not hardcode expected values when they can be computed from the test inputs. This makes tests self-documenting — the reader sees *how* the value is derived, not just what it should be. It also means tests adapt automatically when inputs change.

```java
// Bad — magic number, reader must mentally compute 2*25 + 1*50
var request = createSimpleOrderRequest("cust-1", null,
        createLineItemRequest("prod-1", "Widget", 2, "25.00"),
        createLineItemRequest("prod-2", "Gadget", 1, "50.00"));
OrderResponse order = client.createOrder(request);
assertThat(order.subtotal()).isEqualByComparingTo("100.00");

// Good — expected value derived from inputs, documents the business rule
assertThat(order.subtotal()).isEqualByComparingTo(expectedSubtotal(request));
assertThat(order.customerId()).isEqualTo(request.customerId());
assertThat(order.items()).hasSize(request.items().size());
```

For business rules like discounts, compute the expected value in the test to codify the rule:

```java
var subtotal = expectedSubtotal(request);
var expectedDiscount = subtotal.multiply(new BigDecimal("0.10"));  // SAVE10 = 10% off
var expectedTotal = subtotal.subtract(expectedDiscount);

assertThat(order.discount()).isEqualByComparingTo(expectedDiscount);
assertThat(order.total()).isEqualByComparingTo(expectedTotal);
```

Use helper methods for common computations:

```java
private static BigDecimal expectedSubtotal(CreateOrderRequest request) {
    return request.items().stream()
            .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

### Deserialize message payloads to typed records

When asserting on messages sent to queues (SQS) or topics (SNS), deserialize the message body to a formal type and assert on fields — not string fragments.

Define payload types as records in your production code (the service serializes them, so they're part of the contract):

```java
// In your fulfillment package
public record FulfillmentPayload(long orderId, String customerId, int itemCount) {}

// In your notification package
public record OrderConfirmedEvent(String event, long orderId, String customerId, BigDecimal total) {}
```

In tests, deserialize and assert on fields:

```java
// Bad — fragile string matching, doesn't validate structure
assertThat(sqsRequest.messageBody()).contains("cust-fulfill");
assertThat(sqsRequest.messageBody()).contains("\"itemCount\":1");

// Good — typed deserialization, asserts on fields, compares to request/response properties
var payload = deserialize(sqsRequest.messageBody(), FulfillmentPayload.class);
assertThat(payload.orderId()).isEqualTo(order.id());
assertThat(payload.customerId()).isEqualTo(request.customerId());
assertThat(payload.itemCount()).isEqualTo(request.items().size());
```

A simple `deserialize` helper keeps tests readable:

```java
@Autowired
private ObjectMapper objectMapper;

private <T> T deserialize(String json, Class<T> type) {
    try {
        return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
        throw new RuntimeException("Failed to deserialize: " + json, e);
    }
}
```

This approach validates the serialization contract — if the payload structure changes, tests fail at deserialization rather than silently passing with stale string matches.

## Available test doubles

All are autowired via `BaseIntegrationTest`:

| Field | Type | Pattern |
|-------|------|---------|
| `testPaymentClient` | `TestPaymentClient` | Interface-level |
| `testInventoryClient` | `TestInventoryClient` | Interface-level |
| `testSqsClient` | `TestSqsClient` | SDK-level |
| `testSnsClient` | `TestSnsClient` | SDK-level |
| `testS3Client` | `TestS3Client` | SDK-level |

All are automatically reset between tests via `@BeforeEach` in `BaseIntegrationTest`.

## Simulating failures

All test doubles support `throwWhen` — configure a predicate on the request to throw an exception:

```java
// SDK-level: predicate receives the SDK request object
testSqsClient.throwWhen(
        req -> true,
        () -> SqsException.builder().message("Service unavailable").build());

// Interface-level: predicate receives a request record bundling method parameters
testInventoryClient.throwWhen(
        req -> req.orderId().equals("42"),
        () -> new RuntimeException("Connection refused"));
```

This tests how your code handles external service failures. See `integrating-external-sdk` and `integrating-external-api` for details.

## Prerequisites

Postgres must be running before tests start. See `setting-up-docker-for-tests` for setup.

```bash
docker-compose up -d    # Start Postgres
./gradlew test          # Run tests
```

## Conventions

- Use `@Nested` classes to group related scenarios
- Use `@DisplayName` for readable test names
- Use `create*` helper methods for building requests (e.g., `createSimpleOrderRequest(...)`, `createLineItemRequest(...)`)
- Use AssertJ (`assertThat`, `assertThatThrownBy`) for all assertions
- Test both success and failure paths
- Verify that failure paths do NOT trigger downstream side effects
- Typed test clients assert success status by default; error responses throw exceptions

## Reference

See `src/test/java/.../controller/OrderApiIntegrationTest.java` for a complete example.
See `src/test/java/.../support/clients/OrderClient.java` for the typed test client pattern.
