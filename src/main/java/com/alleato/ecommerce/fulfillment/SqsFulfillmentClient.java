package com.alleato.ecommerce.fulfillment;

import com.alleato.ecommerce.ordering.config.OrderingConfiguration;
import com.alleato.tracing.TraceAttributes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * AWS SQS implementation of {@link FulfillmentClient}.
 *
 * Sends fulfillment messages to an SQS FIFO queue for async processing
 * by a downstream fulfillment service. In tests, the injected {@link SqsClient}
 * is a test implementation that records calls and validates queue URLs.
 */
@Component
public class SqsFulfillmentClient implements FulfillmentClient {

    private final SqsClient sqsClient;
    private final OrderingConfiguration.Fulfillment config;

    public SqsFulfillmentClient(SqsClient sqsClient,
                               OrderingConfiguration.Fulfillment config) {
        this.sqsClient = sqsClient;
        this.config = config;
    }

    @Override
    public void enqueue(String message, String deduplicationId) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(config.getQueueUrl())
                .messageBody(message)
                .messageGroupId("order-fulfillment")
                .messageDeduplicationId(deduplicationId)
                .messageAttributes(TraceAttributes.sqsAttributes())
                .build());
    }
}
