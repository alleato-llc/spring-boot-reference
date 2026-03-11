package com.alleato.ecommerce.ordering.pricing;

import java.math.BigDecimal;

public record PricingResult(BigDecimal subtotal, BigDecimal discount, BigDecimal total) {}
