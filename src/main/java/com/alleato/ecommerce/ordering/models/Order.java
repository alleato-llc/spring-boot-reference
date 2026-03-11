package com.alleato.ecommerce.ordering.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column
    private String paymentTransactionId;

    @Column
    private String inventoryReservationId;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLineItem> lineItems = new ArrayList<>();

    protected Order() {}

    public Order(String customerId) {
        this.customerId = customerId;
        this.status = OrderStatus.PENDING;
        this.subtotal = BigDecimal.ZERO;
        this.discount = BigDecimal.ZERO;
        this.total = BigDecimal.ZERO;
        this.createdAt = Instant.now();
    }

    public void addLineItem(String productId, String productName, int quantity, BigDecimal unitPrice) {
        OrderLineItem item = new OrderLineItem(this, productId, productName, quantity, unitPrice);
        lineItems.add(item);
    }

    // --- Getters ---

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getTotal() { return total; }
    public String getPaymentTransactionId() { return paymentTransactionId; }
    public String getInventoryReservationId() { return inventoryReservationId; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderLineItem> getLineItems() { return lineItems; }

    // --- Fluent mutators (set field, return this) ---

    public Order withStatus(OrderStatus status) {
        this.status = status;
        return this;
    }

    public Order withSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
        return this;
    }

    public Order withDiscount(BigDecimal discount) {
        this.discount = discount;
        return this;
    }

    public Order withTotal(BigDecimal total) {
        this.total = total;
        return this;
    }

    public Order withPaymentTransactionId(String paymentTransactionId) {
        this.paymentTransactionId = paymentTransactionId;
        return this;
    }

    public Order withInventoryReservationId(String inventoryReservationId) {
        this.inventoryReservationId = inventoryReservationId;
        return this;
    }
}
