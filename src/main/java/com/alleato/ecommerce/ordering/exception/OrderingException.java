package com.alleato.ecommerce.ordering.exception;

import java.util.Map;

/**
 * Abstract base exception for all domain errors.
 *
 * Carries a message (via super) and an optional context map for structured
 * error details. Subclasses represent specific failure categories —
 * {@code @ControllerAdvice} maps each subclass to the appropriate HTTP status.
 */
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

    public Map<String, Object> getContext() {
        return context;
    }
}
