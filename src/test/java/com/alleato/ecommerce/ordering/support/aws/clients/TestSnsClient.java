package com.alleato.ecommerce.ordering.support.aws.clients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.NotFoundException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Test implementation of the AWS SNS client. Instead of calling real SNS, it: - Validates that the
 * topic ARN is one of the known/configured topics - Records all Publish calls for test assertion -
 * Returns realistic responses - Throws realistic errors for unknown topic ARNs - Supports
 * configurable exception rules to simulate runtime failures
 *
 * <p>This allows integration tests to use the real {@link
 * com.alleato.ecommerce.ordering.notification.SnsNotificationClient} implementation while
 * intercepting the actual AWS API calls.
 */
public class TestSnsClient implements SnsClient {

  private final Set<String> knownTopicArns;
  private final List<PublishRequest> publishedMessages = new ArrayList<>();
  private final List<ExceptionRule<PublishRequest>> exceptionRules = new ArrayList<>();
  private int messageIdCounter = 0;

  public TestSnsClient(Set<String> knownTopicArns) {
    this.knownTopicArns = knownTopicArns;
  }

  @Override
  public PublishResponse publish(PublishRequest request) {
    for (var rule : exceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    if (!knownTopicArns.contains(request.topicArn())) {
      throw NotFoundException.builder()
          .message("Topic does not exist: " + request.topicArn())
          .build();
    }

    publishedMessages.add(request);
    messageIdCounter++;

    return PublishResponse.builder().messageId("test-sns-msg-" + messageIdCounter).build();
  }

  @Override
  public String serviceName() {
    return "sns";
  }

  @Override
  public void close() {}

  // --- Test configuration ---

  /**
   * Configure an exception to be thrown when the given condition matches a request. Useful for
   * simulating SNS outages, throttling, or authorization failures.
   */
  public void throwWhen(
      Predicate<PublishRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptionRules.add(new ExceptionRule<>(condition, exception));
  }

  // --- Test assertions ---

  public List<PublishRequest> getPublishedMessages() {
    return Collections.unmodifiableList(publishedMessages);
  }

  public PublishRequest getLastPublishedMessage() {
    if (publishedMessages.isEmpty()) {
      throw new IllegalStateException("No SNS messages published");
    }
    return publishedMessages.get(publishedMessages.size() - 1);
  }

  public int getMessageCount() {
    return publishedMessages.size();
  }

  public void reset() {
    publishedMessages.clear();
    exceptionRules.clear();
    messageIdCounter = 0;
  }

  private record ExceptionRule<T>(
      Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
