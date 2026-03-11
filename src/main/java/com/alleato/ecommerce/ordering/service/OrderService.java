package com.alleato.ecommerce.ordering.service;

import com.alleato.ecommerce.ordering.invoicing.InvoiceService;
import com.alleato.ecommerce.ordering.models.*;
import com.alleato.ecommerce.ordering.fulfillment.FulfillmentClient;
import com.alleato.ecommerce.ordering.fulfillment.FulfillmentPayload;
import com.alleato.ecommerce.ordering.inventory.InventoryClient;
import com.alleato.ecommerce.ordering.inventory.InventoryClient.ReservationItem;
import com.alleato.ecommerce.ordering.notification.NotificationClient;
import com.alleato.ecommerce.ordering.notification.OrderConfirmedEvent;
import com.alleato.ecommerce.ordering.payment.PaymentClient;
import com.alleato.ecommerce.ordering.payment.PaymentClient.PaymentResult;
import com.alleato.ecommerce.ordering.pricing.PricingCalculator;
import com.alleato.ecommerce.ordering.pricing.PricingResult;
import com.alleato.ecommerce.ordering.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates the order workflow:
 *  1. Build the order from the request
 *  2. Price it (via PricingCalculator)
 *  3. Persist it
 *  4. Charge payment (via PaymentClient)
 *  5. Reserve inventory (via InventoryClient)
 *  6. Generate invoice (via InvoiceService)
 *  7. Publish notification (via NotificationClient)
 *  8. Enqueue fulfillment job (via FulfillmentClient / SQS)
 *
 * This is the primary target for integration tests — we want to verify that
 * calling the API results in the correct DB state, payment charge, inventory
 * reservation, invoice storage, notification publication, and fulfillment enqueue.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingCalculator pricingCalculator;
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;
    private final NotificationClient notificationClient;
    private final InvoiceService invoiceService;
    private final FulfillmentClient fulfillmentClient;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository,
                        PricingCalculator pricingCalculator,
                        PaymentClient paymentClient,
                        InventoryClient inventoryClient,
                        NotificationClient notificationClient,
                        InvoiceService invoiceService,
                        FulfillmentClient fulfillmentClient,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.pricingCalculator = pricingCalculator;
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.notificationClient = notificationClient;
        this.invoiceService = invoiceService;
        this.fulfillmentClient = fulfillmentClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order(request.customerId());

        for (CreateOrderRequest.LineItemRequest item : request.items()) {
            order.addLineItem(item.productId(), item.productName(), item.quantity(), item.unitPrice());
        }

        PricingResult pricing = pricingCalculator.calculate(order.getLineItems(), request.promoCode());
        order.withSubtotal(pricing.subtotal())
                .withDiscount(pricing.discount())
                .withTotal(pricing.total());

        // Persist so we get an ID before charging
        order = orderRepository.save(order);

        // Charge payment
        String idempotencyKey = "order-" + order.getId();
        PaymentResult result = paymentClient.charge(
                order.getCustomerId(), order.getTotal(), idempotencyKey);

        if (result.success()) {
            order.withStatus(OrderStatus.CONFIRMED)
                    .withPaymentTransactionId(result.transactionId());

            // Reserve inventory via external inventory service
            List<ReservationItem> reservationItems = order.getLineItems().stream()
                    .map(li -> new ReservationItem(li.getProductId(), li.getQuantity()))
                    .toList();
            var confirmation = inventoryClient.reserveItems(
                    String.valueOf(order.getId()), reservationItems);
            order.withInventoryReservationId(confirmation.reservationId());

            // Generate and store invoice
            invoiceService.generateAndStore(order);

            // Publish event
            var event = new OrderConfirmedEvent(
                    "ORDER_CONFIRMED", order.getId(), order.getCustomerId(), order.getTotal());
            notificationClient.publish("order-events", serialize(event));

            // Enqueue fulfillment job for async processing
            var payload = new FulfillmentPayload(
                    order.getId(), order.getCustomerId(), order.getLineItems().size());
            fulfillmentClient.enqueue(serialize(payload), "fulfill-order-" + order.getId());
        } else {
            order.withStatus(OrderStatus.PAYMENT_FAILED);
        }

        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long id) {
        return orderRepository.findByIdWithLineItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(Long id) {
            super("Order not found: " + id);
        }
    }
}
