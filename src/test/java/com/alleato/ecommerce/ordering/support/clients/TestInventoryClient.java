package com.alleato.ecommerce.ordering.support.clients;

import com.alleato.ecommerce.inventory.InventoryClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * In-memory test implementation of {@link InventoryClient}.
 *
 * <p>No HTTP involved — this records reservation calls and returns configurable responses so
 * integration tests can verify the order workflow correctly interacts with the inventory service.
 *
 * <p>Supports: - Configuring which products are out of stock - Configurable exception rules to
 * simulate arbitrary failures - Recording all reservation calls for assertion - Returning realistic
 * confirmation responses
 */
public class TestInventoryClient implements InventoryClient {

  private final Set<String> outOfStockProducts = new HashSet<>();
  private final List<ReservationCall> reservations = new ArrayList<>();
  private final List<ExceptionRule> exceptionRules = new ArrayList<>();
  private int reservationCounter = 0;

  @Override
  public ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items) {
    // Check exception rules first — simulates runtime failures
    var request = new ReservationRequest(orderId, List.copyOf(items));
    for (var rule : exceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    // Check for configured out-of-stock products
    for (ReservationItem item : items) {
      if (outOfStockProducts.contains(item.productId())) {
        throw new InsufficientStockException(item.productId());
      }
    }

    reservationCounter++;
    String reservationId = "test-reservation-" + reservationCounter;
    reservations.add(new ReservationCall(orderId, List.copyOf(items), reservationId));

    return new ReservationConfirmation(reservationId, orderId);
  }

  // --- Test configuration ---

  public void setOutOfStock(String productId) {
    outOfStockProducts.add(productId);
  }

  public void setInStock(String productId) {
    outOfStockProducts.remove(productId);
  }

  /**
   * Configure an exception to be thrown when the given condition matches a request. Useful for
   * simulating service outages, timeouts, or other runtime failures.
   *
   * <p>Example: simulate a total service outage:
   *
   * <pre>{@code
   * testInventoryClient.throwWhen(
   *     req -> true,
   *     () -> new RuntimeException("Connection refused")
   * );
   * }</pre>
   *
   * <p>Example: fail only for a specific order:
   *
   * <pre>{@code
   * testInventoryClient.throwWhen(
   *     req -> req.orderId().equals("42"),
   *     () -> new InsufficientStockException("prod-1")
   * );
   * }</pre>
   */
  public void throwWhen(
      Predicate<ReservationRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptionRules.add(new ExceptionRule(condition, exception));
  }

  // --- Test assertions ---

  public List<ReservationCall> getReservations() {
    return Collections.unmodifiableList(reservations);
  }

  public ReservationCall getLastReservation() {
    if (reservations.isEmpty()) {
      throw new IllegalStateException("No reservations recorded");
    }
    return reservations.get(reservations.size() - 1);
  }

  public int getReservationCount() {
    return reservations.size();
  }

  public void reset() {
    reservations.clear();
    outOfStockProducts.clear();
    exceptionRules.clear();
    reservationCounter = 0;
  }

  /** The parameters passed to a {@code reserveItems} call, for use with {@link #throwWhen}. */
  public record ReservationRequest(String orderId, List<ReservationItem> items) {}

  /** A recorded reservation call, including the generated reservation ID. */
  public record ReservationCall(
      String orderId, List<ReservationItem> items, String reservationId) {}

  private record ExceptionRule(
      Predicate<ReservationRequest> condition, Supplier<? extends RuntimeException> exception) {}
}
