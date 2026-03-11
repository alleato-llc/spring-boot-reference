# Order Retrieval

## What

Fetch an existing order by ID. Returns the full order with line items, pricing, and status.

## How

### User flow

1. Client sends `GET /api/orders/{id}`
2. System looks up the order in the database
3. If found: returns 200 with the full order response
4. If not found: returns 404

### Data flow

```
GET /api/orders/{id}
    │
    ▼
OrderController.getOrder(id)
    │
    ▼
OrderService.getOrder(id)
    │
    ▼
OrderRepository.findById(id)
    │
    ├── found → OrderResponse.from(order) → 200 OK
    └── not found → OrderNotFoundException → 404 NOT FOUND
```

## Architecture

### Design decisions

- **Eager loading**: Line items are fetched with the order (JPA `@OneToMany` with default fetch). The `open-in-view` is disabled (`spring.jpa.open-in-view: false`), so all data must be loaded within the service transaction.
- **DTO mapping**: `OrderResponse.from(Order)` is a static factory on the response record. The controller never exposes JPA entities directly.

### Core models

| Model | Type | Purpose |
|---|---|---|
| `Order` | JPA Entity | Fetched from database |
| `OrderLineItem` | JPA Entity | Loaded with order |
| `OrderResponse` | Record (DTO) | API output |
| `OrderService.OrderNotFoundException` | Exception | Mapped to 404 by controller |

### Core types

| Type | Layer | Role |
|---|---|---|
| `OrderController` | Controller | HTTP mapping, exception handling |
| `OrderService` | Service | Lookup + not-found handling |
| `OrderRepository` | Repository | `findById` (Spring Data built-in) |

### File organization

Same files as order creation — no additional files needed. The retrieval path is a subset of the creation infrastructure.

## Configuration

No additional configuration beyond database connection properties.

## Dependencies

- **Postgres**: Order lookup

## Testing

### Test file

`src/test/java/com/alleato/ecommerce/ordering/controller/OrderApiIntegrationTest.java`

### Scenarios covered

| Nested class | Scenario |
|---|---|
| `GetOrder` | 404 for non-existent order ID |

Note: The happy path for retrieval is implicitly tested in `CreateOrderSuccess` — after creating an order, the response includes the full order data. A dedicated "get after create" test could be added for completeness.

### Adding a new test case

1. Add a `@Test` method in the `GetOrder` nested class
2. Optionally create an order first via `orderClient.createOrder()`
3. Fetch via `orderClient.getOrder(id)`
4. Assert on the response

## Maintenance

- If new fields are added to `Order`, update `OrderResponse.from()` to include them
- If line item data changes, update `OrderResponse.LineItemResponse`

## Limitations

- **No pagination**: Not applicable for single-order retrieval, but a "list orders" endpoint would need it
- **No filtering**: No query parameters for status filtering or date ranges
- **No caching**: Every request hits the database
