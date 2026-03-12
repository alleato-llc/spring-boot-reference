package com.alleato.tracing;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Builds message attributes containing trace context (traceId, spanId) from MDC. Used by SQS and
 * SNS clients to propagate correlation IDs on outgoing messages.
 */
public final class TraceAttributes {

  private TraceAttributes() {}

  public static Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue>
      sqsAttributes() {
    var builder =
        new HashMap<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue>();
    var traceId = MDC.get("traceId");
    if (traceId != null) {
      builder.put(
          "traceId",
          software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(traceId)
              .build());
    }
    var spanId = MDC.get("spanId");
    if (spanId != null) {
      builder.put(
          "spanId",
          software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(spanId)
              .build());
    }
    return Map.copyOf(builder);
  }

  public static Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue>
      snsAttributes() {
    var builder =
        new HashMap<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue>();
    var traceId = MDC.get("traceId");
    if (traceId != null) {
      builder.put(
          "traceId",
          software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(traceId)
              .build());
    }
    var spanId = MDC.get("spanId");
    if (spanId != null) {
      builder.put(
          "spanId",
          software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(spanId)
              .build());
    }
    return Map.copyOf(builder);
  }
}
