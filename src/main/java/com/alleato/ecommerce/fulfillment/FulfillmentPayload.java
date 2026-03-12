package com.alleato.ecommerce.fulfillment;

public record FulfillmentPayload(long orderId, String customerId, int itemCount) {}
