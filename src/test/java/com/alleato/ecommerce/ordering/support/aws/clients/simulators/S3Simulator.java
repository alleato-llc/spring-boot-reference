package com.alleato.ecommerce.ordering.support.aws.clients.simulators;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Request;

/**
 * Simulates an S3 service for integration tests. Produces a mock {@link S3Client} that validates
 * buckets, stores objects in memory, and supports configurable failures.
 *
 * <p>This is a stateful simulator. The primary API reflects the current state of the simulated
 * store ({@link #findObject}, {@link #hasObject}). A unified request log preserves full SDK
 * metadata for all operations.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var s3Simulator = new S3Simulator("order-invoices");
 * var s3Client = s3Simulator.simulate();
 *
 * // s3Client is injected into production code via @Bean
 * // After production code runs:
 * assertThat(s3Simulator.hasObject("order-invoices", "invoices/order-123.pdf")).isTrue();
 * assertThat(s3Simulator.findObject("order-invoices", "invoices/order-123.pdf")).isPresent();
 *
 * // Request log with pattern matching — full SDK metadata preserved
 * assertThat(s3Simulator.hasRequest(r -> r instanceof PutObjectRequest put
 *         && put.key().endsWith(".pdf")
 *         && put.contentType().equals("application/pdf"))).isTrue();
 * }</pre>
 */
public class S3Simulator {

  private final Set<String> knownBuckets;
  private final Map<String, byte[]> objects = new HashMap<>();
  private final List<S3Request> requests = new ArrayList<>();
  private final ExpectedException<S3Request> exceptions = new ExpectedException<>();
  private S3Client mock;

  public S3Simulator(String... buckets) {
    this.knownBuckets = Set.of(buckets);
  }

  /**
   * Returns the simulated {@link S3Client}. Always returns the same instance. Register this as a
   * {@code @Bean} in test configuration.
   */
  public S3Client simulate() {
    if (mock == null) {
      mock = mock(S3Client.class);
      when(mock.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenAnswer(inv -> handlePut(inv.getArgument(0), inv.getArgument(1)));
      when(mock.getObject(any(GetObjectRequest.class)))
          .thenAnswer(inv -> handleGet(inv.getArgument(0)));
    }
    return mock;
  }

  // --- State API ---

  public Optional<byte[]> findObject(String bucket, String key) {
    return Optional.ofNullable(objects.get(bucket + "/" + key));
  }

  public boolean hasObject(String bucket, String key) {
    return objects.containsKey(bucket + "/" + key);
  }

  public int objectCount() {
    return objects.size();
  }

  // --- Request log API ---

  public List<S3Request> getRequests() {
    return Collections.unmodifiableList(requests);
  }

  public boolean hasRequest(Predicate<S3Request> predicate) {
    return requests.stream().anyMatch(predicate);
  }

  // --- Failure simulation ---

  public void throwOnPut(
      Predicate<PutObjectRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptions.throwWhen(r -> r instanceof PutObjectRequest put && condition.test(put), exception);
  }

  public void throwOnGet(
      Predicate<GetObjectRequest> condition, Supplier<? extends RuntimeException> exception) {
    exceptions.throwWhen(r -> r instanceof GetObjectRequest get && condition.test(get), exception);
  }

  // --- Lifecycle ---

  public void reset() {
    objects.clear();
    requests.clear();
    exceptions.reset();
  }

  // --- Internal ---

  private PutObjectResponse handlePut(PutObjectRequest request, RequestBody requestBody) {
    exceptions.checkRules(request);
    validateBucket(request.bucket());

    byte[] content;
    try {
      content = requestBody.contentStreamProvider().newStream().readAllBytes();
    } catch (Exception e) {
      throw new RuntimeException("Failed to read request body", e);
    }

    objects.put(request.bucket() + "/" + request.key(), content);
    requests.add(request);

    return PutObjectResponse.builder().eTag("\"test-etag-" + objects.size() + "\"").build();
  }

  private ResponseInputStream<GetObjectResponse> handleGet(GetObjectRequest request) {
    exceptions.checkRules(request);
    validateBucket(request.bucket());

    String fullKey = request.bucket() + "/" + request.key();
    byte[] content = objects.get(fullKey);

    if (content == null) {
      throw NoSuchKeyException.builder()
          .message("The specified key does not exist: " + request.key())
          .build();
    }

    requests.add(request);

    GetObjectResponse response =
        GetObjectResponse.builder().contentLength((long) content.length).build();

    return new ResponseInputStream<>(
        response, AbortableInputStream.create(new ByteArrayInputStream(content)));
  }

  private void validateBucket(String bucket) {
    if (!knownBuckets.contains(bucket)) {
      throw NoSuchBucketException.builder()
          .message("The specified bucket does not exist: " + bucket)
          .build();
    }
  }
}
