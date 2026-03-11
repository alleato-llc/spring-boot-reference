package com.alleato.ecommerce.ordering.notification;

/**
 * Contract for publishing notifications/events. Real impl uses AWS SNS.
 * Test fakes capture published messages for assertion.
 */
public interface NotificationClient {

    void publish(String topic, String messageBody);
}
