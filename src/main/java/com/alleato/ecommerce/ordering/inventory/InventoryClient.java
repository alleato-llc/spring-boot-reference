package com.alleato.ecommerce.ordering.inventory;

import java.util.List;

/**
 * Client interface for the external inventory service.
 *
 * This encapsulates a partner/internal REST API that has no public SDK.
 * The real implementation ({@link HttpInventoryClient}) handles HTTP concerns.
 * In tests, an in-memory implementation records calls without any HTTP.
 */
public interface InventoryClient {

    /**
     * Reserve inventory for an order. The inventory service holds the items
     * until the order ships or the reservation expires.
     *
     * @return a reservation confirmation with a reservation ID
     * @throws InsufficientStockException if any item cannot be reserved
     */
    ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items);

    record ReservationItem(String productId, int quantity) {}

    record ReservationConfirmation(String reservationId, String orderId) {}

    class InsufficientStockException extends RuntimeException {
        private final String productId;

        public InsufficientStockException(String productId) {
            super("Insufficient stock for product: " + productId);
            this.productId = productId;
        }

        public String getProductId() { return productId; }
    }
}
