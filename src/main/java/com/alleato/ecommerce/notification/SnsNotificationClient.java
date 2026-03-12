package com.alleato.ecommerce.notification;

import com.alleato.tracing.TraceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * AWS SNS implementation of {@link NotificationClient}.
 *
 * Publishes messages to SNS topics. The topic ARN is constructed from a
 * configurable prefix and the logical topic name. In tests, the injected
 * {@link SnsClient} is a test implementation that records calls and validates topic ARNs.
 */
@Component
public class SnsNotificationClient implements NotificationClient {

    private final SnsClient snsClient;
    private final String topicArnPrefix;

    public SnsNotificationClient(SnsClient snsClient,
                                  @Value("${notification.topic.arn-prefix}") String topicArnPrefix) {
        this.snsClient = snsClient;
        this.topicArnPrefix = topicArnPrefix;
    }

    @Override
    public void publish(String topic, String messageBody) {
        String topicArn = topicArnPrefix + ":" + topic;
        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(messageBody)
                .messageAttributes(TraceAttributes.snsAttributes())
                .build());
    }
}
