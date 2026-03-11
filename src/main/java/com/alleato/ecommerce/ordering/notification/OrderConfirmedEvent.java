package com.alleato.ecommerce.ordering.notification;

import java.math.BigDecimal;

public record OrderConfirmedEvent(String event, long orderId, String customerId, BigDecimal total) {}
