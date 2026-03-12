package com.alleato.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleato.ecommerce.ordering.models.Order;
import com.alleato.ecommerce.ordering.models.OrderLineItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * UNIT TEST — no Spring context, no database, no fakes.
 *
 * <p>PricingCalculator is pure business logic with no external dependencies. Unit tests are the
 * right choice here because: - The value is in verifying the pricing ALGORITHM (combinations of
 * promos, bulk discounts, caps) - There's nothing to integrate with — no DB, no HTTP, no external
 * services - Tests run in milliseconds and cover many edge cases easily
 *
 * <p>This is the "contract" we're testing: given these inputs, the pricing rules produce these
 * outputs. We don't care HOW it computes — just that the contract holds.
 */
class PricingCalculatorTest {

  private PricingCalculator pricingCalculator;

  @BeforeEach
  void setUp() {
    pricingCalculator = new PricingCalculator();
  }

  @Nested
  @DisplayName("subtotal calculation")
  class SubtotalCalculation {

    @Test
    void calculatesSubtotalFromLineItems() {
      var items = lineItems(item("widget", 2, "25.00"), item("gadget", 1, "50.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      assertThat(result.subtotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void singleItem() {
      var items = lineItems(item("thing", 3, "10.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      assertThat(result.subtotal()).isEqualByComparingTo("30.00");
    }
  }

  @Nested
  @DisplayName("promo code discounts")
  class PromoCodeDiscounts {

    @Test
    void save10AppliesTenPercent() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, "SAVE10");

      assertThat(result.discount()).isEqualByComparingTo("20.00");
      assertThat(result.total()).isEqualByComparingTo("180.00");
    }

    @Test
    void save20AppliesTwentyPercent() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, "SAVE20");

      assertThat(result.discount()).isEqualByComparingTo("40.00");
      assertThat(result.total()).isEqualByComparingTo("160.00");
    }

    @Test
    void flat50AppliesFiftyDollarsOff() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, "FLAT50");

      assertThat(result.discount()).isEqualByComparingTo("50.00");
      assertThat(result.total()).isEqualByComparingTo("150.00");
    }

    @Test
    void unknownPromoCodeHasNoEffect() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, "INVALID");

      assertThat(result.discount()).isEqualByComparingTo("0.00");
      assertThat(result.total()).isEqualByComparingTo("200.00");
    }

    @Test
    void nullPromoCodeHasNoEffect() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      assertThat(result.discount()).isEqualByComparingTo("0.00");
    }

    @Test
    void promoCodeIsCaseInsensitive() {
      var items = lineItems(item("item", 1, "200.00"));

      PricingResult result = pricingCalculator.calculate(items, "save10");

      assertThat(result.discount()).isEqualByComparingTo("20.00");
    }
  }

  @Nested
  @DisplayName("bulk discount")
  class BulkDiscount {

    @Test
    void appliesTenPercentOver500() {
      var items = lineItems(item("expensive", 1, "600.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      // 10% of 600 = 60
      assertThat(result.discount()).isEqualByComparingTo("60.00");
      assertThat(result.total()).isEqualByComparingTo("540.00");
    }

    @Test
    void doesNotApplyAtExactly500() {
      var items = lineItems(item("item", 1, "500.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      assertThat(result.discount()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesAt501() {
      var items = lineItems(item("item", 1, "501.00"));

      PricingResult result = pricingCalculator.calculate(items, null);

      assertThat(result.discount()).isEqualByComparingTo("50.10");
    }
  }

  @Nested
  @DisplayName("stacked discounts")
  class StackedDiscounts {

    @Test
    void promoAndBulkDiscountsStack() {
      var items = lineItems(item("item", 1, "600.00"));

      PricingResult result = pricingCalculator.calculate(items, "SAVE10");

      // SAVE10 = 10% of 600 = 60; Bulk = 10% of 600 = 60; Total discount = 120
      assertThat(result.discount()).isEqualByComparingTo("120.00");
      assertThat(result.total()).isEqualByComparingTo("480.00");
    }

    @Test
    void discountCannotExceedSubtotal() {
      var items = lineItems(item("item", 1, "501.00"));

      PricingResult result = pricingCalculator.calculate(items, "SAVE20");

      assertThat(result.total()).isNotNegative();
      assertThat(result.discount()).isLessThanOrEqualTo(result.subtotal());
    }
  }

  // --- Helpers ---

  private List<OrderLineItem> lineItems(ItemSpec... items) {
    Order order = new Order("test-customer");
    for (ItemSpec item : items) {
      order.addLineItem(item.productId, item.name, item.qty, new BigDecimal(item.price));
    }
    return order.getLineItems();
  }

  private ItemSpec item(String name, int qty, String price) {
    return new ItemSpec("prod-" + name, name, qty, price);
  }

  private record ItemSpec(String productId, String name, int qty, String price) {}
}
