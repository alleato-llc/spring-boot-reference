package com.alleato.ecommerce.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Real Stripe integration. Only active in production profile.
 * In tests, the fake replaces this via dependency injection.
 */
@Component
@Profile("!test")
public class StripePaymentClient implements PaymentClient {

    @Override
    public PaymentResult charge(String customerId, BigDecimal amount, String idempotencyKey) {
        // In a real app: call Stripe API with the idempotency key
        throw new UnsupportedOperationException(
                "Stripe integration not implemented — this is a reference project. " +
                "Tests use TestPaymentClient instead.");
    }
}
