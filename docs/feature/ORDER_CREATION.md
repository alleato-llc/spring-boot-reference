# Order Creation

## What

The primary workflow of the system. A customer submits an order via REST API, and the system orchestrates pricing, persistence, payment, inventory reservation, invoice generation, notification, and fulfillment enqueueing.

## How

### User flow

1. Client sends `POST /api/orders` with customer ID, line items, and optional promo code
2. System calculates pricing (subtotals, discounts)
3. Order is persisted to the database with status `PENDING`
4. Payment is charged via the payment provider
5. If payment succeeds:
   - Status updated to `CONFIRMED`
   - Inventory is reserved
   - Invoice is generated and stored in S3
   - Order confirmation notification is published to SNS
   - Fulfillment job is enqueued to SQS
6. If payment fails:
   - Status updated to `PAYMENT_FAILED`
   - No downstream actions occur
7. Client receives the order response with final status

### Data flow

```
CreateOrderRequest (JSON)
    │
    ▼
OrderController.createOrder()
    │ validates @Valid
    ▼
OrderService.createOrder()          @Transactional
    │
    ├─1─► PricingCalculator.calculate()
    │         lineItems + promoCode → PricingResult (subtotal, discount, total)
    │
    ├─2─► OrderRepository.save()
    │         Order entity (PENDING) + OrderLineItems → Postgres
    │
    ├─3─► PaymentClient.charge()
    │         customerId + total + idempotencyKey → PaymentResult
    │         if failed: update status to PAYMENT_FAILED, return early
    │
    ├─4─► InventoryClient.reserveItems()
    │         orderId + items → ReservationConfirmation
    │
    ├─5─► InvoiceService.generateAndStore()
    │         order → builds text → DocumentClient.store() → S3
    │
    ├─6─► NotificationClient.publish()
    │         OrderConfirmedEvent → SNS topic
    │
    └─7─► FulfillmentClient.enqueue()
              FulfillmentPayload → SQS FIFO queue
    │
    ▼
OrderResponse (JSON, 201 CREATED)
```

## Architecture

### Design decisions

- **Sequential orchestration**: Steps execute in order within a single `@Transactional` method. If any step fails, the transaction rolls back. This is a simplification — a production system might use saga pattern or outbox pattern for reliability.
- **Idempotency key for payment**: Uses `"order-" + order.getId()` as the idempotency key, preventing duplicate charges on retry.
- **Deduplication ID for fulfillment**: Uses `"order-" + order.getId()` for SQS FIFO deduplication.
- **Early return on payment failure**: If payment fails, the method returns immediately without executing downstream steps. Tests verify this with negative assertions.

### Core models

| Model | Type | Purpose |
|---|---|---|
| `CreateOrderRequest` | Record (DTO) | API input — customer ID, line items, promo code |
| `CreateOrderRequest.LineItemRequest` | Record (DTO) | Nested — product ID, name, quantity, unit price |
| `Order` | JPA Entity | Persisted order with status, pricing, transaction IDs |
| `OrderLineItem` | JPA Entity | Persisted line item with computed `getLineTotal()` |
| `OrderResponse` | Record (DTO) | API output — full order with line items |
| `OrderStatus` | Enum | PENDING, CONFIRMED, PAYMENT_FAILED, SHIPPED, CANCELLED |
| `PricingResult` | Record | Calculator output — subtotal, discount, total |
| `PaymentClient.PaymentResult` | Record | Payment outcome — success, transactionId, failureReason |
| `InventoryClient.ReservationConfirmation` | Record | Reservation outcome — reservationId, orderId |
| `FulfillmentPayload` | Record | SQS message body — orderId, customerId, itemCount |
| `OrderConfirmedEvent` | Record | SNS message body — event type, orderId, customerId, total |

### Core types

| Type | Layer | Role |
|---|---|---|
| `OrderController` | Controller | HTTP mapping, delegates to service |
| `OrderService` | Service | Workflow orchestration, `@Transactional` |
| `PricingCalculator` | Standalone logic | Pure computation — pricing, discounts |
| `OrderRepository` | Repository | Spring Data JPA interface |
| `PaymentClient` | Client interface | Payment boundary |
| `InventoryClient` | Client interface | Inventory boundary |
| `InvoiceService` | Service | Invoice generation + storage orchestration |
| `DocumentClient` | Client interface | Document storage boundary |
| `NotificationClient` | Client interface | Event publishing boundary |
| `FulfillmentClient` | Client interface | Job enqueueing boundary |

### File organization

```
src/main/java/com/alleato/ecommerce/ordering/
├── controller/OrderController.java
├── service/OrderService.java
├── models/
│   ├── CreateOrderRequest.java
│   ├── Order.java
│   ├── OrderLineItem.java
│   ├── OrderResponse.java
│   └── OrderStatus.java
├── pricing/
│   ├── PricingCalculator.java
│   └── PricingResult.java
├── repository/OrderRepository.java
├── payment/PaymentClient.java
├── inventory/InventoryClient.java
├── invoicing/
│   ├── InvoiceService.java
│   └── DocumentClient.java
├── notification/
│   ├── NotificationClient.java
│   └── OrderConfirmedEvent.java
└── fulfillment/
    ├── FulfillmentClient.java
    └── FulfillmentPayload.java
```

## Configuration

| Property | Purpose |
|---|---|
| `fulfillment.queue.url` | SQS FIFO queue URL for fulfillment jobs |
| `notification.topic.arn-prefix` | SNS topic ARN prefix for notifications |
| `spring.datasource.*` | Postgres connection for order persistence |

## Dependencies

- **Postgres**: Order and line item persistence
- **Payment provider** (Stripe): Charge processing
- **Inventory API**: Stock reservation
- **S3**: Invoice document storage
- **SNS**: Order confirmation notifications
- **SQS**: Fulfillment job queue

## Testing

### Test file

`src/test/java/com/alleato/ecommerce/ordering/controller/OrderApiIntegrationTest.java`

### Scenarios covered

| Nested class | Scenarios |
|---|---|
| `CreateOrderSuccess` | Happy path — all steps execute, response is CONFIRMED, all side effects verified |
| `CreateOrderWithPromo` | Promo code discount applied, discounted amount charged |
| `CreateOrderPaymentFailure` | Payment declined — status PAYMENT_FAILED, no downstream effects |
| `CreateOrderExternalServiceFailure` | SQS down → 500, Inventory down → 500 |
| `GetOrder` | 404 for non-existent order |

### Adding a new test case

1. Add a `@Test` method in the appropriate `@Nested` class (or create a new one)
2. Set up test doubles if needed (e.g., `testPaymentClient.throwWhen(...)`)
3. Send request via `orderClient.createOrder(request)`
4. Assert on the response (status, body)
5. Assert on side effects (DB state, test double recordings)

## Maintenance

- **Adding a new step**: Add the call in `OrderService.createOrder()` in the correct position. Add negative assertions in failure tests to verify the new step doesn't execute when upstream steps fail.
- **Changing step order**: Update `OrderService` and verify all integration tests still pass. The order of assertions in tests reflects the expected execution order.

## Limitations

- **No retry logic**: If an external service call fails, the entire transaction rolls back. No automatic retry or compensation.
- **No async processing**: All steps are synchronous within the HTTP request. A production system would likely make some steps async (notification, fulfillment).
- **No partial failure handling**: If invoice storage fails after payment succeeds, the payment is not refunded. A production system would need compensation logic or a saga pattern.
- **Single transaction**: The entire workflow runs in one database transaction. Long-running external calls (payment, inventory) hold the transaction open.
