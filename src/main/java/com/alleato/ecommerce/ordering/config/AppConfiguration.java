package com.alleato.ecommerce.ordering.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfiguration {

  @Bean
  public OrderingConfiguration.Fulfillment fulfillmentConfig(OrderingConfiguration config) {
    return config.getFulfillment();
  }

  @Bean
  public OrderingConfiguration.Notification notificationConfig(OrderingConfiguration config) {
    return config.getNotification();
  }
}
