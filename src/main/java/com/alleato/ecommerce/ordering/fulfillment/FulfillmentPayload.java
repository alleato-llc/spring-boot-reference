package com.alleato.ecommerce.ordering.fulfillment;

public record FulfillmentPayload(long orderId, String customerId, int itemCount) {}
