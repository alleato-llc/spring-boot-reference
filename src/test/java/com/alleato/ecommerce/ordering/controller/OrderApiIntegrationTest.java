package com.alleato.ecommerce.ordering.controller;

import com.alleato.ecommerce.ordering.fulfillment.FulfillmentPayload;
import com.alleato.ecommerce.ordering.models.CreateOrderRequest;
import com.alleato.ecommerce.ordering.models.CreateOrderRequest.LineItemRequest;
import com.alleato.ecommerce.ordering.models.OrderResponse;
import com.alleato.ecommerce.ordering.models.OrderStatus;
import com.alleato.ecommerce.ordering.notification.OrderConfirmedEvent;
import com.alleato.ecommerce.ordering.support.clients.TestInventoryClient.ReservationCall;
import com.alleato.ecommerce.ordering.support.clients.TestPaymentClient.ChargeInvocation;
import com.alleato.ecommerce.ordering.inventory.InventoryClient.ReservationItem;
import com.alleato.ecommerce.ordering.support.BaseIntegrationTest;
import com.alleato.ecommerce.ordering.support.clients.OrderClient.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INTEGRATION TEST — boots the full Spring app against Postgres (via docker-compose),
 * with AWS SDK clients replaced by test implementations.
 *
 * The real service implementations (SqsFulfillmentClient, SnsNotificationClient,
 * S3DocumentClient) are exercised in these tests — only the underlying AWS SDK
 * calls are intercepted by the test clients.
 *
 * Uses {@link com.alleato.ecommerce.ordering.support.clients.OrderClient} for typed HTTP calls.
 */
class OrderApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static String randomId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static CreateOrderRequest createSimpleOrderRequest(String customerId, String promoCode, LineItemRequest... items) {
        return new CreateOrderRequest(customerId, List.of(items), promoCode);
    }

    private static LineItemRequest createLineItemRequest(String productId, String name, int qty, String price) {
        return new LineItemRequest(productId, name, qty, new BigDecimal(price));
    }

    private static BigDecimal expectedSubtotal(CreateOrderRequest request) {
        return request.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize: " + json, e);
        }
    }

    @Nested
    @DisplayName("POST /api/orders — successful order creation")
    class CreateOrderSuccess {

        private String customerId;

        @BeforeEach
        void setUp() {
            customerId = randomId("cust");
        }

        @Test
        void createsOrderAndReturnsConfirmedStatus() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 2, "25.00"),
                    createLineItemRequest(randomId("prod"), "Gadget", 1, "50.00"));

            OrderResponse order = orderClient.createOrder(request);

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.customerId()).isEqualTo(request.customerId());
            assertThat(order.subtotal()).isEqualByComparingTo(expectedSubtotal(request));
            assertThat(order.total()).isEqualByComparingTo(expectedSubtotal(request));
            assertThat(order.discount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(order.paymentTransactionId()).startsWith("test-txn-");
            assertThat(order.items()).hasSize(request.items().size());
        }

        @Test
        void persistsOrderToDatabase() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "75.00"));

            OrderResponse created = orderClient.createOrder(request);

            OrderResponse fetched = orderClient.getOrder(created.id());
            assertThat(fetched.id()).isEqualTo(created.id());
            assertThat(fetched.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(fetched.customerId()).isEqualTo(request.customerId());
        }

        @Test
        void chargesPaymentWithCorrectAmount() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 4, "25.00"));

            orderClient.createOrder(request);

            assertThat(testPaymentClient.getInvocationCount()).isEqualTo(1);
            ChargeInvocation charge = testPaymentClient.getLastInvocation();
            assertThat(charge.customerId()).isEqualTo(request.customerId());
            assertThat(charge.amount()).isEqualByComparingTo(expectedSubtotal(request));
            assertThat(charge.idempotencyKey()).startsWith("order-");
        }

        @Test
        void publishesOrderConfirmedNotificationViaSns() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00"));

            OrderResponse order = orderClient.createOrder(request);

            assertThat(testSnsClient.getMessageCount()).isEqualTo(1);
            var snsRequest = testSnsClient.getLastPublishedMessage();
            assertThat(snsRequest.topicArn()).isEqualTo("arn:aws:sns:us-east-1:123456789:order-events");

            var event = deserialize(snsRequest.message(), OrderConfirmedEvent.class);
            assertThat(event.event()).isEqualTo("ORDER_CONFIRMED");
            assertThat(event.orderId()).isEqualTo(order.id());
            assertThat(event.customerId()).isEqualTo(request.customerId());
            assertThat(event.total()).isEqualByComparingTo(expectedSubtotal(request));
        }

        @Test
        void storesInvoiceDocumentViaS3() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 2, "30.00"));

            OrderResponse order = orderClient.createOrder(request);

            assertThat(testS3Client.getPutCount()).isEqualTo(1);
            PutObjectRequest s3Request = testS3Client.getLastPutRequest();
            assertThat(s3Request.bucket()).isEqualTo("order-invoices");
            assertThat(s3Request.key()).isEqualTo("invoices/order-%d.txt".formatted(order.id()));
            assertThat(s3Request.contentType()).isEqualTo("text/plain");

            byte[] content = testS3Client.getStoredObject("order-invoices", s3Request.key());
            String invoice = new String(content, StandardCharsets.UTF_8);
            assertThat(invoice).contains(request.customerId());
            assertThat(invoice).contains(request.items().getFirst().productName());
            assertThat(invoice).contains("$%s".formatted(expectedSubtotal(request)));
        }

        @Test
        void reservesInventoryForOrderItems() {
            var item1 = createLineItemRequest(randomId("prod"), "Widget", 2, "25.00");
            var item2 = createLineItemRequest(randomId("prod"), "Gadget", 1, "50.00");
            var request = createSimpleOrderRequest(customerId, null, item1, item2);

            OrderResponse order = orderClient.createOrder(request);

            assertThat(order.inventoryReservationId()).startsWith("test-reservation-");
            assertThat(testInventoryClient.getReservationCount()).isEqualTo(1);
            ReservationCall reservation = testInventoryClient.getLastReservation();
            assertThat(reservation.orderId()).isEqualTo(String.valueOf(order.id()));
            assertThat(reservation.items()).containsExactlyInAnyOrder(
                    new ReservationItem(item1.productId(), item1.quantity()),
                    new ReservationItem(item2.productId(), item2.quantity()));
        }

        @Test
        void enqueuesFulfillmentJobViaSqs() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 3, "20.00"));

            OrderResponse order = orderClient.createOrder(request);

            assertThat(testSqsClient.getMessageCount()).isEqualTo(1);
            var sqsRequest = testSqsClient.getLastSentMessage();
            assertThat(sqsRequest.queueUrl())
                    .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo");
            assertThat(sqsRequest.messageGroupId()).isEqualTo("order-fulfillment");
            assertThat(sqsRequest.messageDeduplicationId()).isEqualTo("fulfill-order-" + order.id());

            var payload = deserialize(sqsRequest.messageBody(), FulfillmentPayload.class);
            assertThat(payload.orderId()).isEqualTo(order.id());
            assertThat(payload.customerId()).isEqualTo(request.customerId());
            assertThat(payload.itemCount()).isEqualTo(request.items().size());
        }
    }

    @Nested
    @DisplayName("POST /api/orders — with promo code")
    class CreateOrderWithPromo {

        @Test
        void appliesPromoDiscountAndChargesDiscountedAmount() {
            var request = createSimpleOrderRequest(randomId("cust"), "SAVE10",
                    createLineItemRequest(randomId("prod"), "Widget", 1, "200.00"));

            OrderResponse order = orderClient.createOrder(request);

            var subtotal = expectedSubtotal(request);
            var expectedDiscount = subtotal.multiply(new BigDecimal("0.10"));
            var expectedTotal = subtotal.subtract(expectedDiscount);

            assertThat(order.subtotal()).isEqualByComparingTo(subtotal);
            assertThat(order.discount()).isEqualByComparingTo(expectedDiscount);
            assertThat(order.total()).isEqualByComparingTo(expectedTotal);

            ChargeInvocation charge = testPaymentClient.getLastInvocation();
            assertThat(charge.amount()).isEqualByComparingTo(expectedTotal);
        }
    }

    @Nested
    @DisplayName("POST /api/orders — payment failure")
    class CreateOrderPaymentFailure {

        private String customerId;

        @BeforeEach
        void setUp() {
            customerId = randomId("cust");
            testPaymentClient.willFail("Card declined");
        }

        @Test
        void setsStatusToPaymentFailedWhenChargeDeclined() {
            var request = createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00"));

            OrderResponse order = orderClient.createOrder(request);

            assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.paymentTransactionId()).isNull();
        }

        @Test
        void doesNotPublishNotificationOnPaymentFailure() {
            orderClient.createOrder(createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00")));

            assertThat(testSnsClient.getMessageCount()).isZero();
        }

        @Test
        void doesNotStoreInvoiceOnPaymentFailure() {
            orderClient.createOrder(createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00")));

            assertThat(testS3Client.getPutCount()).isZero();
        }

        @Test
        void doesNotReserveInventoryOnPaymentFailure() {
            orderClient.createOrder(createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00")));

            assertThat(testInventoryClient.getReservationCount()).isZero();
        }

        @Test
        void doesNotEnqueueFulfillmentOnPaymentFailure() {
            orderClient.createOrder(createSimpleOrderRequest(customerId, null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00")));

            assertThat(testSqsClient.getMessageCount()).isZero();
        }
    }

    @Nested
    @DisplayName("POST /api/orders — external service failure")
    class CreateOrderExternalServiceFailure {

        @Test
        void returns500WhenFulfillmentQueueIsDown() {
            testSqsClient.throwWhen(
                    req -> true,
                    () -> SqsException.builder().message("Service unavailable").build());

            assertThatThrownBy(() -> orderClient.createOrder(createSimpleOrderRequest(randomId("cust"), null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00"))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        @Test
        void returns500WhenInventoryServiceIsDown() {
            testInventoryClient.throwWhen(
                    req -> true,
                    () -> new RuntimeException("Connection refused"));

            assertThatThrownBy(() -> orderClient.createOrder(createSimpleOrderRequest(randomId("cust"), null,
                    createLineItemRequest(randomId("prod"), "Widget", 1, "50.00"))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @Nested
    @DisplayName("GET /api/orders/{id}")
    class GetOrder {

        @Test
        void returns404ForNonExistentOrder() {
            long nonExistentId = Long.MAX_VALUE;
            assertThatThrownBy(() -> orderClient.getOrder(nonExistentId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        var apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(apiEx.body()).contains("Order not found: " + nonExistentId);
                    });
        }
    }
}
