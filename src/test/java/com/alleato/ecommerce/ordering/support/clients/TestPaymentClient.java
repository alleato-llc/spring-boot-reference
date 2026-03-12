package com.alleato.ecommerce.ordering.support.clients;

import com.alleato.ecommerce.payment.PaymentClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A test-friendly implementation of {@link PaymentClient} that: - Implements the same contract as
 * the real gateway - Records every invocation so tests can assert on what was charged - Allows
 * tests to configure success/failure behavior - Supports configurable exception rules to simulate
 * runtime failures
 *
 * <p>This is a "fake" (not a mock): it has real behavior and internal state, but is designed for
 * testing rather than production use.
 */
public class TestPaymentClient implements PaymentClient {

  private boolean shouldSucceed = true;
  private String failureReason = "Insufficient funds";
  private int transactionCounter = 0;

  private final List<ChargeInvocation> invocations = new ArrayList<>();
  private final List<ExceptionRule> exceptionRules = new ArrayList<>();

  @Override
  public PaymentResult charge(String customerId, BigDecimal amount, String idempotencyKey) {
    // Check exception rules first — simulates runtime failures
    var request = new ChargeRequest(customerId, amount, idempotencyKey);
    for (var rule : exceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    ChargeInvocation invocation = new ChargeInvocation(customerId, amount, idempotencyKey);
    invocations.add(invocation);

    if (shouldSucceed) {
      transactionCounter++;
      String txnId = "test-txn-" + transactionCounter;
      return PaymentResult.success(txnId);
    } else {
      return PaymentResult.failure(failureReason);
    }
  }

  // --- Test configuration ---

  public void willSucceed() {
    this.shouldSucceed = true;
  }

  public void willFail(String reason) {
    this.shouldSucceed = false;
    this.failureReason = reason;
  }

  /**
   * Configure an exception to be thrown when the given condition matches a charge request. Useful
   * for simulating payment gateway outages, network timeouts, etc.
   */
  public void throwWhen(
      Predicate<ChargeRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptionRules.add(new ExceptionRule(condition, exception));
  }

  // --- Test assertions ---

  public List<ChargeInvocation> getInvocations() {
    return Collections.unmodifiableList(invocations);
  }

  public ChargeInvocation getLastInvocation() {
    if (invocations.isEmpty()) {
      throw new IllegalStateException("No payment charges recorded");
    }
    return invocations.get(invocations.size() - 1);
  }

  public int getInvocationCount() {
    return invocations.size();
  }

  public void reset() {
    invocations.clear();
    exceptionRules.clear();
    transactionCounter = 0;
    shouldSucceed = true;
    failureReason = "Insufficient funds";
  }

  /** The parameters passed to a charge call, for use with {@link #throwWhen}. */
  public record ChargeRequest(String customerId, BigDecimal amount, String idempotencyKey) {}

  /** A recorded charge invocation. */
  public record ChargeInvocation(String customerId, BigDecimal amount, String idempotencyKey) {}

  private record ExceptionRule(
      Predicate<ChargeRequest> condition, Supplier<? extends RuntimeException> exception) {}
}
