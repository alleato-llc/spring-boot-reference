package com.alleato.ecommerce.ordering.models;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotEmpty @Valid List<LineItemRequest> items,
        String promoCode
) {
    public record LineItemRequest(
            @NotBlank String productId,
            @NotBlank String productName,
            int quantity,
            java.math.BigDecimal unitPrice
    ) {}
}
