package com.alleato.ecommerce.ordering.support.aws.clients.simulators;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.NotFoundException;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Simulates an SNS service for integration tests. Produces a mock {@link SnsClient}
 * that validates topic ARNs, records published messages, and supports configurable failures.
 *
 * <p>This is a stateless simulator — publications are fire-and-forget. The domain API
 * ({@link #getPublications()}, {@link #findPublication(Predicate)}) is the complete history.
 *
 * <p>Usage:
 * <pre>{@code
 * var snsSimulator = new SnsSimulator("arn:aws:sns:us-east-1:123:my-topic");
 * var snsClient = snsSimulator.simulate();
 *
 * // snsClient is injected into production code via @Bean
 * // After production code runs:
 * assertThat(snsSimulator.publicationCount()).isEqualTo(1);
 * assertThat(snsSimulator.findPublication(p -> p.message().contains("order-123"))).isPresent();
 * }</pre>
 */
public class SnsSimulator {

    private final Set<String> knownTopicArns;
    private final List<PublishRequest> requests = new ArrayList<>();
    private final ExpectedException<PublishRequest> exceptions = new ExpectedException<>();
    private SnsClient mock;
    private int messageIdCounter = 0;

    public SnsSimulator(String... topicArns) {
        this.knownTopicArns = Set.of(topicArns);
    }

    /**
     * Returns the simulated {@link SnsClient}. Always returns the same instance.
     * Register this as a {@code @Bean} in test configuration.
     */
    public SnsClient simulate() {
        if (mock == null) {
            mock = mock(SnsClient.class);
            when(mock.publish(any(PublishRequest.class)))
                    .thenAnswer(inv -> handlePublish(inv.getArgument(0)));
        }
        return mock;
    }

    // --- Domain API ---

    public List<PublishRequest> getPublications() {
        return Collections.unmodifiableList(requests);
    }

    public Optional<PublishRequest> findPublication(Predicate<PublishRequest> predicate) {
        return requests.stream().filter(predicate).findFirst();
    }

    public int publicationCount() {
        return requests.size();
    }

    // --- Failure simulation ---

    public void throwWhen(Predicate<PublishRequest> condition,
                          Supplier<? extends RuntimeException> exception) {
        exceptions.throwWhen(condition, exception);
    }

    // --- Lifecycle ---

    public void reset() {
        requests.clear();
        exceptions.reset();
        messageIdCounter = 0;
    }

    // --- Internal ---

    private PublishResponse handlePublish(PublishRequest request) {
        exceptions.checkRules(request);

        if (!knownTopicArns.contains(request.topicArn())) {
            throw NotFoundException.builder()
                    .message("Topic does not exist: " + request.topicArn())
                    .build();
        }

        requests.add(request);
        messageIdCounter++;

        return PublishResponse.builder()
                .messageId("test-sns-msg-" + messageIdCounter)
                .build();
    }
}
