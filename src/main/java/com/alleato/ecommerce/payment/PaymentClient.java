package com.alleato.ecommerce.payment;

import java.math.BigDecimal;

/**
 * Contract for processing payments. Real implementations call Stripe/etc.
 * Test implementations (fakes) record invocations for assertion.
 */
public interface PaymentClient {

    PaymentResult charge(String customerId, BigDecimal amount, String idempotencyKey);

    record PaymentResult(boolean success, String transactionId, String failureReason) {

        public static PaymentResult success(String transactionId) {
            return new PaymentResult(true, transactionId, null);
        }

        public static PaymentResult failure(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
