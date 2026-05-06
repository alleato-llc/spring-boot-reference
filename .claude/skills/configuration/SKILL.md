---
name: configuration
description: Configuration management for Spring Boot applications. @ConfigurationProperties POJOs with project-name prefix, environment variable binding, nested config decomposition, local dev defaults, profile-based overrides, sensitive value protection. Use when defining application configuration, adding environment-bound properties, or setting up profile-based overrides.
version: 1.0.0
---

# Configuration

## Rules

- All application config lives in a `@ConfigurationProperties` POJO — no `@Value` for application properties
- Config properties use a project-name prefix (e.g., `ordering.*`) so environment variables are namespaced: `ORDERING_FULFILLMENT_QUEUE_URL`
- Environment variables are the primary config source in deployed environments — YAML files provide local dev defaults
- Sensitive values (passwords, API keys, tokens) must never appear in committed config files — use environment variables or a secrets manager
- Let the type system enforce property shape — ports are `int`, URLs are `String`, durations are `Duration`, flags are `boolean`
- Provide reasonable defaults that "just work" for local development — a developer should be able to clone, start Docker, and run tests without setting environment variables
- Use Spring Boot profiles for environment-specific overrides — `application.yml` is the base, `application-{profile}.yml` overrides

## Package layout

- Config classes live in `{org}.{project}.{core_domain}.config` (e.g., `com.alleato.ecommerce.ordering.config/`)
- The root config class is named `{ProjectName}Configuration` (e.g., `OrderingConfiguration`)

## @ConfigurationProperties POJO

One root class per application, annotated with `@ConfigurationProperties(prefix = "{project-name}")`. Nested static classes group related properties.

```java
@ConfigurationProperties(prefix = "ordering")
public class OrderingConfiguration {

    private final Fulfillment fulfillment;
    private final Notification notification;

    public OrderingConfiguration(Fulfillment fulfillment, Notification notification) {
        this.fulfillment = fulfillment;
        this.notification = notification;
    }

    public Fulfillment getFulfillment() { return fulfillment; }
    public Notification getNotification() { return notification; }

    public static class Fulfillment {
        private String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo";

        public String getQueueUrl() { return queueUrl; }
        public void setQueueUrl(String queueUrl) { this.queueUrl = queueUrl; }
    }

    public static class Notification {
        private String topicArnPrefix = "arn:aws:sns:us-east-1:123456789";

        public String getTopicArnPrefix() { return topicArnPrefix; }
        public void setTopicArnPrefix(String topicArnPrefix) { this.topicArnPrefix = topicArnPrefix; }
    }
}
```

### Why `@ConfigurationProperties` over `@Value`

- **Type-safe** — the compiler catches typos; `@Value("${fulfillmnet.queue.url}")` fails silently at startup
- **Grouped** — related properties are one object, not scattered across constructors
- **Testable** — construct the POJO directly in tests without Spring context
- **Documented** — the class IS the documentation of what config the app needs
- **Discoverable** — one place to find all application config, not grep for `@Value`

### Enable in the application class

```java
@SpringBootApplication(scanBasePackages = "${org}.${project}")
@ConfigurationPropertiesScan
public class OrderingApplication { }
```

`@ConfigurationPropertiesScan` discovers `@ConfigurationProperties` classes automatically — no need for `@EnableConfigurationProperties` or `@Bean` methods.

## Environment variable binding

Spring Boot's relaxed binding maps YAML keys to environment variables automatically:

| YAML key | Environment variable | Java field |
|---|---|---|
| `ordering.fulfillment.queue-url` | `ORDERING_FULFILLMENT_QUEUE_URL` | `fulfillment.queueUrl` |
| `ordering.notification.topic-arn-prefix` | `ORDERING_NOTIFICATION_TOPIC_ARN_PREFIX` | `notification.topicArnPrefix` |
| `ordering.database.port` | `ORDERING_DATABASE_PORT` | `database.port` |

The project-name prefix (`ordering.*`) namespaces all environment variables under `ORDERING_*`, preventing collisions with other services.

### Precedence

Environment variables override YAML values. The full precedence (highest to lowest):

1. Environment variables (`ORDERING_FULFILLMENT_QUEUE_URL=...`)
2. Profile-specific YAML (`application-prod.yml`)
3. Base YAML (`application.yml`)
4. Defaults in the `@ConfigurationProperties` class

This means: YAML provides local dev defaults, environment variables override in production.

## Defaults for local development

Config should "just work" after `git clone && docker-compose up -d && ./gradlew test`. Provide defaults that target the local Docker environment:

```java
public static class Database {
    private String url = "jdbc:postgresql://localhost:5432/orders";
    private String username = "orders";
    private String password = "orders";
    private int port = 5432;

    // getters and setters
}
```

Rules for defaults:
- **Local infrastructure**: `localhost` URLs, default ports, dev credentials
- **Feature flags**: default to the safe/conservative behavior
- **Timeouts**: default to generous values (avoid flaky local builds)
- **Never default sensitive production values** — production secrets come from environment variables, never from defaults

## Sensitive values

Sensitive values (passwords, API keys, tokens, connection strings with credentials) must never be committed to config files.

### In YAML: reference environment variables

```yaml
# Good — value comes from environment, falls back to local dev default
spring:
  datasource:
    password: ${ORDERING_DATABASE_PASSWORD:orders}

# Bad — production secret hardcoded
spring:
  datasource:
    password: pr0d_s3cret!
```

The `${ENV_VAR:default}` syntax provides a local dev fallback while requiring an environment variable in production.

### In @ConfigurationProperties: no default for secrets

```java
public static class ExternalApi {
    private String apiKey;  // No default — must be provided via env var

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
```

If a property has no default and no value is provided, Spring Boot fails fast at startup — which is the correct behavior for missing secrets.

## Type safety

Use Java types to enforce property shape. Spring Boot converts environment variable strings automatically:

```java
public static class Server {
    private int port = 8080;                          // Integer — rejects "abc"
    private Duration requestTimeout = Duration.ofSeconds(30);  // Duration — accepts "30s", "5m"
    private boolean metricsEnabled = true;            // Boolean — accepts "true", "false"
    private URI callbackUrl;                          // URI — validates format
    private List<String> allowedOrigins = List.of("http://localhost:3000");
}
```

If an environment variable doesn't match the expected type (e.g., `ORDERING_SERVER_PORT=abc`), Spring Boot fails at startup with a clear error.

## Decomposition

Start with one root config class. When it grows, decompose.

### When to decompose

- A nested class exceeds ~5 properties
- A nested class is needed by multiple unrelated components
- The root class exceeds ~3–4 nested groups

### How to decompose

Extract the nested class to its own `@ConfigurationProperties` with a more specific prefix:

```java
// Before: one big class
@ConfigurationProperties(prefix = "ordering")
public class OrderingConfiguration {
    // ... 4+ nested classes, getting unwieldy
}

// After: separate class for the complex group
@ConfigurationProperties(prefix = "ordering.fulfillment")
public class FulfillmentConfig {
    private String queueUrl;
    private String messageGroupId = "order-fulfillment";
    private Duration visibilityTimeout = Duration.ofSeconds(30);
    private int maxRetries = 3;
    private Duration retryDelay = Duration.ofSeconds(5);

    // getters and setters
}
```

The root class keeps simple groups; complex groups get their own class. Both approaches bind to the same YAML/env var namespace.

## Profiles

Use profiles for environment-specific configuration. `application.yml` is the base; `application-{profile}.yml` overrides specific values.

### File structure

```
src/main/resources/
├── application.yml              Base config (local dev defaults)
└── application-prod.yml         Production overrides (if needed)

src/test/resources/
└── application-test.yml         Test profile overrides
```

### Rules

- **Base YAML has local dev defaults** — works out of the box for developers
- **Production uses environment variables**, not a `application-prod.yml` full of secrets
- **`application-prod.yml` is for non-secret structural differences** — e.g., disabling Flyway clean, enabling structured logging, adjusting sampling rates
- **Test profile overrides** point to the docker-compose test database and provide test-specific values (known AWS ARNs, queue URLs for test SDK clients)
- **Do not create per-environment profiles** (`application-staging.yml`, `application-qa.yml`) — these lead to config drift. Use environment variables for values that differ between environments.

### Example base config

```yaml
# application.yml — local dev defaults
spring:
  application:
    name: ordering
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: ${ORDERING_DATABASE_USERNAME:orders}
    password: ${ORDERING_DATABASE_PASSWORD:orders}

ordering:
  fulfillment:
    queue-url: ${ORDERING_FULFILLMENT_QUEUE_URL:https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo}
  notification:
    topic-arn-prefix: ${ORDERING_NOTIFICATION_TOPIC_ARN_PREFIX:arn:aws:sns:us-east-1:123456789}
```

### Example test config

```yaml
# application-test.yml — test overrides
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/orders_test
    username: test
    password: test
```

Test config overrides only what differs from the base. Test-specific AWS values (queue URLs, topic ARNs) match the known resources configured in test SDK clients.

## Injecting config into components

Components receive the specific nested config they need — not the entire root config:

```java
@Component
public class SqsFulfillmentClient implements FulfillmentClient {

    private final SqsClient sqsClient;
    private final OrderingConfiguration.Fulfillment config;

    public SqsFulfillmentClient(SqsClient sqsClient, OrderingConfiguration.Fulfillment config) {
        this.sqsClient = sqsClient;
        this.config = config;
    }

    @Override
    public void enqueue(String message, String deduplicationId) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(config.getQueueUrl())
                .messageBody(message)
                .messageGroupId("order-fulfillment")
                .messageDeduplicationId(deduplicationId)
                .build());
    }
}
```

Spring Boot does not automatically register nested config classes as beans. To inject `OrderingConfiguration.Fulfillment` directly, expose it via a `@Bean` method:

```java
@Configuration
public class AppConfiguration {

    @Bean
    public OrderingConfiguration.Fulfillment fulfillmentConfig(OrderingConfiguration config) {
        return config.getFulfillment();
    }

    @Bean
    public OrderingConfiguration.Notification notificationConfig(OrderingConfiguration config) {
        return config.getNotification();
    }
}
```

This keeps component constructors focused on the config they actually need, rather than receiving the entire root config and navigating to the relevant section.

## What NOT to configure

Not everything needs to be configurable. Over-configuration adds complexity without value.

- **Internal implementation details** — message group IDs, serialization formats, internal queue names that never change
- **Values that only differ in tests** — use test doubles and test profiles, not config flags
- **Feature toggles masquerading as config** — if you're adding `ordering.enable-notifications=true/false`, that's a feature flag system, not application config

A good rule: if a value has never changed across environments and is unlikely to, it's a constant, not config.

## Checklist

When adding configuration:

- [ ] Property is in a `@ConfigurationProperties` class, not `@Value`
- [ ] Prefix is the project name (e.g., `ordering.*`)
- [ ] Local dev default is provided (works after `git clone && docker-compose up -d`)
- [ ] Sensitive values have no committed default — they require an environment variable
- [ ] Types match the domain (`int` for ports, `Duration` for timeouts, `boolean` for flags)
- [ ] YAML uses `${ENV_VAR:default}` syntax for values that change per environment
- [ ] Test profile overrides only what differs from the base
