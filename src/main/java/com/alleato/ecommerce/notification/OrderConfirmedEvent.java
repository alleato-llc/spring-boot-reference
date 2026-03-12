package com.alleato.ecommerce.notification;

import java.math.BigDecimal;

public record OrderConfirmedEvent(
    String event, long orderId, String customerId, BigDecimal total) {}
