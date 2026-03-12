package com.alleato.ecommerce.ordering.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ordering")
public class OrderingConfiguration {

  private final Fulfillment fulfillment;
  private final Notification notification;

  public OrderingConfiguration(Fulfillment fulfillment, Notification notification) {
    this.fulfillment = fulfillment;
    this.notification = notification;
  }

  public Fulfillment getFulfillment() {
    return fulfillment;
  }

  public Notification getNotification() {
    return notification;
  }

  public static class Fulfillment {
    private String queueUrl;

    public String getQueueUrl() {
      return queueUrl;
    }

    public void setQueueUrl(String queueUrl) {
      this.queueUrl = queueUrl;
    }
  }

  public static class Notification {
    private String topicArnPrefix;

    public String getTopicArnPrefix() {
      return topicArnPrefix;
    }

    public void setTopicArnPrefix(String topicArnPrefix) {
      this.topicArnPrefix = topicArnPrefix;
    }
  }
}
