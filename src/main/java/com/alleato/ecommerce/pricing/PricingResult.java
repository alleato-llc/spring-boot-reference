package com.alleato.ecommerce.pricing;

import java.math.BigDecimal;

public record PricingResult(BigDecimal subtotal, BigDecimal discount, BigDecimal total) {}
