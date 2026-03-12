package com.alleato.tracing;

import java.util.Map;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Sets trace context from SQS message attributes into MDC for the duration of a task, ensuring
 * cleanup in a finally block to prevent context leaking between messages.
 */
public final class TraceContext {

  private TraceContext() {}

  public static void run(Map<String, MessageAttributeValue> attributes, Runnable action) {
    setFromAttribute(attributes, "traceId");
    setFromAttribute(attributes, "spanId");
    try {
      action.run();
    } finally {
      MDC.remove("traceId");
      MDC.remove("spanId");
    }
  }

  private static void setFromAttribute(Map<String, MessageAttributeValue> attributes, String key) {
    var attr = attributes.get(key);
    if (attr != null && attr.stringValue() != null) {
      MDC.put(key, attr.stringValue());
    }
  }
}
