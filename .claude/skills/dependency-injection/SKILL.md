---
name: dependency-injection
description: Dependency injection rules for Spring Boot — constructor injection only, no inline dependency construction, @Bean methods own object creation. Use when creating or reviewing components that have dependencies.
---

# Dependency Injection

## Rules

### 1. Constructor injection only

All dependencies are received via the constructor. No `@Autowired` on fields or setters.

```java
// Bad — field injection
@Autowired
private OrderRepository orderRepository;

// Good — constructor injection
private final OrderRepository orderRepository;

public OrderService(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
}
```

**Exception**: `@Autowired` fields in test base classes (e.g., `BaseIntegrationTest`) where constructor injection isn't practical due to inheritance.

### 2. Never construct dependencies inline

No `new SomeService()`, no `RestClient.builder()...build()`, no `SqsClient.create()` inside constructors or methods. Object creation belongs in `@Configuration`/`@Bean` methods.

```java
// Bad — constructs dependency inline
@Component
public class HttpInventoryClient implements InventoryClient {
    private final RestClient restClient;

    public HttpInventoryClient(@Value("${inventory.service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }
}

// Good — receives dependency via injection
@Component
public class HttpInventoryClient implements InventoryClient {
    private final RestClient restClient;

    public HttpInventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }
}
```

Why: inline construction couples the component to a specific implementation, makes testing harder (can't inject a test double), and scatters configuration across the codebase.

### 3. `@Value` is for config values, not for constructing objects

Receiving a `String`, `int`, or other primitive via `@Value` is fine — it's configuration. But don't use that value to `new` or `.builder()` a dependency inside the constructor.

```java
// Good — @Value for config, dependency injected separately
public SqsFulfillmentClient(SqsClient sqsClient,
                            @Value("${fulfillment.queue.url}") String queueUrl) {
    this.sqsClient = sqsClient;
    this.queueUrl = queueUrl;
}

// Bad — @Value used to construct a dependency
public HttpInventoryClient(@Value("${inventory.service.base-url}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
}
```

### 4. `@Bean` methods are the factory

`@Configuration` classes own object creation and wiring. This is where `new`, `.builder()`, and `.create()` belong.

```java
@Configuration
public class AppConfiguration {

    @Bean
    public RestClient inventoryRestClient(
            @Value("${inventory.service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
```

The `@Bean` method takes configuration via `@Value` and produces the fully-constructed object. Components then receive the object via constructor injection — they never know how it was built.

