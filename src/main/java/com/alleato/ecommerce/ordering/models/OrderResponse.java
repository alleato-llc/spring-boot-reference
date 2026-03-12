package com.alleato.ecommerce.ordering.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    Long id,
    String customerId,
    OrderStatus status,
    BigDecimal subtotal,
    BigDecimal discount,
    BigDecimal total,
    String paymentTransactionId,
    String inventoryReservationId,
    Instant createdAt,
    List<LineItemResponse> items) {
  public record LineItemResponse(
      String productId,
      String productName,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal lineTotal) {}

  public static OrderResponse from(Order order) {
    List<LineItemResponse> items =
        order.getLineItems().stream()
            .map(
                li ->
                    new LineItemResponse(
                        li.getProductId(),
                        li.getProductName(),
                        li.getQuantity(),
                        li.getUnitPrice(),
                        li.getLineTotal()))
            .toList();

    return new OrderResponse(
        order.getId(),
        order.getCustomerId(),
        order.getStatus(),
        order.getSubtotal(),
        order.getDiscount(),
        order.getTotal(),
        order.getPaymentTransactionId(),
        order.getInventoryReservationId(),
        order.getCreatedAt(),
        items);
  }
}
