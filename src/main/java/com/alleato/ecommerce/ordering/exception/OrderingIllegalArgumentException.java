package com.alleato.ecommerce.ordering.exception;

import java.util.Map;

public class OrderingIllegalArgumentException extends OrderingException {

  public OrderingIllegalArgumentException(String message) {
    super(message);
  }

  public OrderingIllegalArgumentException(String message, Map<String, Object> context) {
    super(message, context);
  }
}
