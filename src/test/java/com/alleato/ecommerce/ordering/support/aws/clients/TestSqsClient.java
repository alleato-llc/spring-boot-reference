package com.alleato.ecommerce.ordering.support.aws.clients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Test implementation of the AWS SQS client. Instead of calling real SQS, it: - Validates that the
 * queue URL is one of the known/configured queues - Records all SendMessage calls for test
 * assertion - Returns realistic responses - Throws realistic errors for unknown queues - Supports
 * configurable exception rules to simulate runtime failures
 *
 * <p>This allows integration tests to use the real {@link
 * com.alleato.ecommerce.ordering.fulfillment.SqsFulfillmentClient} implementation while
 * intercepting the actual AWS API calls.
 */
public class TestSqsClient implements SqsClient {

  private final Set<String> knownQueueUrls;
  private final List<SendMessageRequest> sentMessages = new ArrayList<>();
  private final List<ExceptionRule<SendMessageRequest>> exceptionRules = new ArrayList<>();
  private int messageIdCounter = 0;

  public TestSqsClient(Set<String> knownQueueUrls) {
    this.knownQueueUrls = knownQueueUrls;
  }

  @Override
  public SendMessageResponse sendMessage(SendMessageRequest request) {
    // Check exception rules first — simulates runtime failures
    for (var rule : exceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    if (!knownQueueUrls.contains(request.queueUrl())) {
      throw QueueDoesNotExistException.builder()
          .message("The specified queue does not exist: " + request.queueUrl())
          .build();
    }

    sentMessages.add(request);
    messageIdCounter++;

    return SendMessageResponse.builder()
        .messageId("test-msg-" + messageIdCounter)
        .sequenceNumber(String.valueOf(messageIdCounter))
        .build();
  }

  @Override
  public String serviceName() {
    return "sqs";
  }

  @Override
  public void close() {}

  // --- Test configuration ---

  /**
   * Configure an exception to be thrown when the given condition matches a request. Useful for
   * simulating SQS outages, throttling, or other runtime failures.
   *
   * <p>Example: simulate a service outage for all requests:
   *
   * <pre>{@code
   * testSqsClient.throwWhen(
   *     req -> true,
   *     () -> SqsException.builder().message("Service unavailable").build()
   * );
   * }</pre>
   */
  public void throwWhen(
      Predicate<SendMessageRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptionRules.add(new ExceptionRule<>(condition, exception));
  }

  // --- Test assertions ---

  public List<SendMessageRequest> getSentMessages() {
    return Collections.unmodifiableList(sentMessages);
  }

  public SendMessageRequest getLastSentMessage() {
    if (sentMessages.isEmpty()) {
      throw new IllegalStateException("No SQS messages sent");
    }
    return sentMessages.get(sentMessages.size() - 1);
  }

  public int getMessageCount() {
    return sentMessages.size();
  }

  public void reset() {
    sentMessages.clear();
    exceptionRules.clear();
    messageIdCounter = 0;
  }

  private record ExceptionRule<T>(
      Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
