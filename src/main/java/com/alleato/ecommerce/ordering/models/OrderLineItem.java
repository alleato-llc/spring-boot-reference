package com.alleato.ecommerce.ordering.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "order_line_items")
public class OrderLineItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @Column(nullable = false)
  private String productId;

  @Column(nullable = false)
  private String productName;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal unitPrice;

  protected OrderLineItem() {}

  public OrderLineItem(
      Order order, String productId, String productName, int quantity, BigDecimal unitPrice) {
    this.order = order;
    this.productId = productId;
    this.productName = productName;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  public Long getId() {
    return id;
  }

  public Order getOrder() {
    return order;
  }

  public String getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public BigDecimal getLineTotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }
}
