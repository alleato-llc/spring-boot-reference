---
name: adding-unit-tests
description: Adds unit tests for pure business logic components that have no external dependencies. No Spring context, no database, no test doubles. Use when testing algorithms, calculations, validation logic, or any class with no injected dependencies.
---

# Adding Unit Tests

Unit tests verify pure business logic — components with no external dependencies (no DB, no HTTP, no Spring context).

## When to use unit tests vs integration tests

| Component | Test type | Why |
|-----------|-----------|-----|
| Pricing algorithm | Unit test | Pure math, no dependencies |
| Validation logic | Unit test | Pure logic |
| Service orchestrating DB + APIs | Integration test | Has external dependencies |
| Controller endpoints | Integration test | Full request lifecycle |

## Structure

- Named `*Test` (e.g., `PricingCalculatorTest`)
- Lives in the **same package** as the class under test
- No Spring annotations — just plain JUnit + AssertJ
- Instantiate the class under test directly in `@BeforeEach`

## Template

```java
package com.alleato.ecommerce.ordering.pricing;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class MyServiceTest {

    private MyService service;

    @BeforeEach
    void setUp() {
        service = new MyService();
    }

    @Nested
    @DisplayName("feature A")
    class FeatureA {

        @Test
        void handlesNormalCase() {
            var result = service.doSomething(input);
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void handlesEdgeCase() {
            var result = service.doSomething(edgeInput);
            assertThat(result).satisfies(r -> {
                // multiple assertions on result
            });
        }
    }
}
```

## Conventions

- Use `@Nested` classes to group by feature/behavior
- Use `@DisplayName` for readable grouping
- Test the **contract** (given inputs -> expected outputs), not the implementation
- Cover: normal cases, edge cases, boundary values, error conditions
- Use AssertJ for all assertions
- Use helper methods to reduce test setup boilerplate

## Reference

See `src/test/java/.../pricing/PricingCalculatorTest.java` for a complete example with nested groups, edge cases, and boundary tests.
