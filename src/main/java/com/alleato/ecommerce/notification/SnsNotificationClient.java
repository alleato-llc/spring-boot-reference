package com.alleato.ecommerce.notification;

import com.alleato.ecommerce.ordering.config.OrderingConfiguration;
import com.alleato.tracing.TraceAttributes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * AWS SNS implementation of {@link NotificationClient}.
 *
 * <p>Publishes messages to SNS topics. The topic ARN is constructed from a configurable prefix and
 * the logical topic name. In tests, the injected {@link SnsClient} is a test implementation that
 * records calls and validates topic ARNs.
 */
@Component
public class SnsNotificationClient implements NotificationClient {

  private final SnsClient snsClient;
  private final OrderingConfiguration.Notification config;

  public SnsNotificationClient(SnsClient snsClient, OrderingConfiguration.Notification config) {
    this.snsClient = snsClient;
    this.config = config;
  }

  @Override
  public void publish(String topic, String messageBody) {
    String topicArn = config.getTopicArnPrefix() + ":" + topic;
    snsClient.publish(
        PublishRequest.builder()
            .topicArn(topicArn)
            .message(messageBody)
            .messageAttributes(TraceAttributes.snsAttributes())
            .build());
  }
}
