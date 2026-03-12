package com.alleato.ecommerce.ordering.support.aws.clients.simulators;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Simulates an SQS service for integration tests. Produces a mock {@link SqsClient} that validates
 * queue URLs, records sent messages, and supports configurable failures.
 *
 * <p>This is a stateless simulator — messages are fire-and-forget. The domain API ({@link
 * #getMessages()}, {@link #findMessage(Predicate)}) is the complete history.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var sqsSimulator = new SqsSimulator("https://sqs.us-east-1.amazonaws.com/123/my-queue.fifo");
 * var sqsClient = sqsSimulator.simulate();
 *
 * // sqsClient is injected into production code via @Bean
 * // After production code runs:
 * assertThat(sqsSimulator.messageCount()).isEqualTo(1);
 * assertThat(sqsSimulator.findMessage(m -> m.messageBody().contains("order-123"))).isPresent();
 * }</pre>
 */
public class SqsSimulator {

  private final Set<String> knownQueueUrls;
  private final List<SendMessageRequest> requests = new ArrayList<>();
  private final ExpectedException<SendMessageRequest> exceptions = new ExpectedException<>();
  private SqsClient mock;
  private int messageIdCounter = 0;

  public SqsSimulator(String... queueUrls) {
    this.knownQueueUrls = Set.of(queueUrls);
  }

  /**
   * Returns the simulated {@link SqsClient}. Always returns the same instance. Register this as a
   * {@code @Bean} in test configuration.
   */
  public SqsClient simulate() {
    if (mock == null) {
      mock = mock(SqsClient.class);
      when(mock.sendMessage(any(SendMessageRequest.class)))
          .thenAnswer(inv -> handleSendMessage(inv.getArgument(0)));
    }
    return mock;
  }

  // --- Domain API ---

  public List<SendMessageRequest> getMessages() {
    return Collections.unmodifiableList(requests);
  }

  public Optional<SendMessageRequest> findMessage(Predicate<SendMessageRequest> predicate) {
    return requests.stream().filter(predicate).findFirst();
  }

  public int messageCount() {
    return requests.size();
  }

  // --- Failure simulation ---

  public void throwWhen(
      Predicate<SendMessageRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptions.throwWhen(condition, exception);
  }

  // --- Lifecycle ---

  public void reset() {
    requests.clear();
    exceptions.reset();
    messageIdCounter = 0;
  }

  // --- Internal ---

  private SendMessageResponse handleSendMessage(SendMessageRequest request) {
    exceptions.checkRules(request);

    if (!knownQueueUrls.contains(request.queueUrl())) {
      throw QueueDoesNotExistException.builder()
          .message("The specified queue does not exist: " + request.queueUrl())
          .build();
    }

    requests.add(request);
    messageIdCounter++;

    return SendMessageResponse.builder()
        .messageId("test-msg-" + messageIdCounter)
        .sequenceNumber(String.valueOf(messageIdCounter))
        .build();
  }
}
