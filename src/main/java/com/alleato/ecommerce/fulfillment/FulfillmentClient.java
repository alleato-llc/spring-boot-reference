package com.alleato.ecommerce.fulfillment;

/**
 * Contract for enqueuing order fulfillment jobs.
 * When an order is confirmed, a fulfillment message is sent to a queue
 * for async processing (picking, packing, shipping).
 *
 * Real implementation uses AWS SQS. Test implementation captures
 * enqueued messages for assertion.
 */
public interface FulfillmentClient {

    /**
     * Enqueue a fulfillment request for the given order.
     *
     * @param message the serialized fulfillment message
     * @param deduplicationId used by SQS FIFO queues to prevent duplicate processing
     */
    void enqueue(String message, String deduplicationId);
}
