# Security Design

## Overview

This is a reference project focused on testing patterns. Security is simplified to keep the focus on the primary patterns being demonstrated. This document describes the current security posture and notes where a production system would differ.

## API security

### Authentication

Not implemented. The REST API is open — no authentication required.

**Production consideration**: Add Spring Security with JWT or OAuth2. The `OrderController` would require authenticated requests, and the customer ID would come from the authentication token rather than the request body.

### Authorization

Not implemented. Any caller can create orders for any customer ID.

**Production consideration**: Implement role-based access control. Customers should only access their own orders. Admin roles for order management.

### Input validation

Implemented via Bean Validation (`@Valid`, `@NotBlank`, `@NotEmpty`) on `CreateOrderRequest`. Invalid requests return 400 Bad Request.

### Rate limiting

Not implemented.

**Production consideration**: Add rate limiting at the API gateway or via Spring Cloud Gateway.

## Data security

### Database

- Credentials are hardcoded in `application.yml` and `docker-compose.yml` (acceptable for a reference/test project)
- **Production consideration**: Use environment variables or a secrets manager (AWS Secrets Manager, HashiCorp Vault)

### Sensitive data

- Payment transaction IDs are stored in the orders table
- No PII beyond customer IDs (which are opaque identifiers in this reference)
- **Production consideration**: Encrypt sensitive columns, implement data retention policies

## External service security

### AWS services (SQS, SNS, S3)

- In tests: replaced by test doubles, no real AWS calls
- In production: would use IAM roles or AWS credentials
- **Production consideration**: Use IAM instance profiles, not long-lived access keys

### Payment (Stripe)

- `StripePaymentClient` is a placeholder (`@Profile("!test")`)
- No real Stripe API calls are made
- **Production consideration**: Use Stripe's idempotency keys (already modeled in `PaymentClient.charge()`), PCI compliance, webhook signature verification

### Inventory API

- `HttpInventoryClient` is a placeholder (`@Profile("!test")`)
- **Production consideration**: Mutual TLS or API key authentication, circuit breaker pattern

## Dependency security

- Dependencies managed via Gradle
- **Production consideration**: Enable dependency vulnerability scanning (Dependabot, Snyk, or OWASP Dependency-Check Gradle plugin)
