package com.alleato.ecommerce.pricing;

import com.alleato.ecommerce.ordering.models.OrderLineItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure business logic — no external dependencies, no mutation.
 * Accepts line items and an optional promo code, returns a PricingResult.
 * This is the kind of component best tested with plain unit tests,
 * because the value is in verifying the algorithm, not the wiring.
 */
@Service
public class PricingCalculator {

    private static final BigDecimal BULK_THRESHOLD = new BigDecimal("500.00");
    private static final BigDecimal BULK_DISCOUNT_RATE = new BigDecimal("0.10");

    /**
     * Calculates subtotal, applies promo + bulk discounts, and returns the result.
     */
    public PricingResult calculate(List<OrderLineItem> lineItems, String promoCode) {
        BigDecimal subtotal = lineItems.stream()
                .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discount = BigDecimal.ZERO;

        // Promo code discounts
        discount = discount.add(calculatePromoDiscount(subtotal, promoCode));

        // Bulk discount: 10% off orders over $500 (stacks with promo)
        if (subtotal.compareTo(BULK_THRESHOLD) > 0) {
            discount = discount.add(subtotal.multiply(BULK_DISCOUNT_RATE));
        }

        // Discount cannot exceed subtotal
        if (discount.compareTo(subtotal) > 0) {
            discount = subtotal;
        }

        discount = discount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP);

        return new PricingResult(subtotal, discount, total);
    }

    BigDecimal calculatePromoDiscount(BigDecimal subtotal, String promoCode) {
        if (promoCode == null || promoCode.isBlank()) {
            return BigDecimal.ZERO;
        }

        return switch (promoCode.toUpperCase()) {
            case "SAVE10" -> subtotal.multiply(new BigDecimal("0.10"));
            case "SAVE20" -> subtotal.multiply(new BigDecimal("0.20"));
            case "FLAT50" -> new BigDecimal("50.00");
            default -> BigDecimal.ZERO;
        };
    }
}
