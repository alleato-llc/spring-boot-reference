package com.alleato.ecommerce.ordering.support.aws.clients.simulators;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Manages configurable exception rules for simulator operations.
 * Rules are checked before an operation proceeds — if any rule's condition
 * matches the request, its exception is thrown.
 *
 * @param <T> the SDK request type (e.g. {@code PutObjectRequest})
 */
public class ExpectedException<T> {

    private final List<ExceptionRule<T>> rules = new ArrayList<>();

    /**
     * Check configured exception rules against the request.
     * Throws the configured exception if any rule's condition matches.
     */
    public void checkRules(T request) {
        for (var rule : rules) {
            if (rule.condition().test(request)) {
                throw rule.exception().get();
            }
        }
    }

    /**
     * Register an exception rule. When {@link #checkRules} encounters a request
     * matching the condition, it throws the supplied exception.
     */
    public void throwWhen(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {
        rules.add(new ExceptionRule<>(condition, exception));
    }

    public void reset() {
        rules.clear();
    }

    private record ExceptionRule<T>(Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
