package com.alleato.ecommerce.ordering.support.aws.clients;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

/**
 * Test implementation of the AWS S3 client. Instead of calling real S3, it: - Validates that the
 * bucket is one of the known/configured buckets - Stores objects in-memory - Records all PutObject
 * calls for test assertion - Returns realistic responses and errors - Supports configurable
 * exception rules to simulate runtime failures
 *
 * <p>This allows integration tests to use the real {@link
 * com.alleato.ecommerce.ordering.invoicing.S3DocumentClient} implementation while intercepting the
 * actual AWS API calls.
 */
public class TestS3Client implements S3Client {

  private final Set<String> knownBuckets;
  private final Map<String, byte[]> objects = new HashMap<>();
  private final List<PutObjectRequest> putRequests = new ArrayList<>();
  private final List<ExceptionRule<PutObjectRequest>> putExceptionRules = new ArrayList<>();
  private final List<ExceptionRule<GetObjectRequest>> getExceptionRules = new ArrayList<>();

  public TestS3Client(Set<String> knownBuckets) {
    this.knownBuckets = knownBuckets;
  }

  @Override
  public PutObjectResponse putObject(PutObjectRequest request, RequestBody requestBody) {
    for (var rule : putExceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    validateBucket(request.bucket());

    // Read the content from the RequestBody
    byte[] content;
    try {
      content = requestBody.contentStreamProvider().newStream().readAllBytes();
    } catch (Exception e) {
      throw new RuntimeException("Failed to read request body", e);
    }

    String fullKey = request.bucket() + "/" + request.key();
    objects.put(fullKey, content);
    putRequests.add(request);

    return PutObjectResponse.builder().eTag("\"test-etag-" + putRequests.size() + "\"").build();
  }

  @Override
  public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
    for (var rule : getExceptionRules) {
      if (rule.condition().test(request)) {
        throw rule.exception().get();
      }
    }

    validateBucket(request.bucket());

    String fullKey = request.bucket() + "/" + request.key();
    byte[] content = objects.get(fullKey);

    if (content == null) {
      throw NoSuchKeyException.builder()
          .message("The specified key does not exist: " + request.key())
          .build();
    }

    GetObjectResponse response =
        GetObjectResponse.builder().contentLength((long) content.length).build();

    return new ResponseInputStream<>(
        response, AbortableInputStream.create(new ByteArrayInputStream(content)));
  }

  @Override
  public String serviceName() {
    return "s3";
  }

  @Override
  public void close() {}

  // --- Test configuration ---

  /**
   * Configure an exception to be thrown when a PutObject request matches the condition. Useful for
   * simulating S3 write failures, access denied, etc.
   */
  public void throwOnPut(
      Predicate<PutObjectRequest> condition, Supplier<? extends RuntimeException> exception) {
    putExceptionRules.add(new ExceptionRule<>(condition, exception));
  }

  /**
   * Configure an exception to be thrown when a GetObject request matches the condition. Useful for
   * simulating S3 read failures, access denied, etc.
   */
  public void throwOnGet(
      Predicate<GetObjectRequest> condition, Supplier<? extends RuntimeException> exception) {
    getExceptionRules.add(new ExceptionRule<>(condition, exception));
  }

  // --- Test assertions ---

  public List<PutObjectRequest> getPutRequests() {
    return Collections.unmodifiableList(putRequests);
  }

  public PutObjectRequest getLastPutRequest() {
    if (putRequests.isEmpty()) {
      throw new IllegalStateException("No S3 put operations recorded");
    }
    return putRequests.get(putRequests.size() - 1);
  }

  public int getPutCount() {
    return putRequests.size();
  }

  public byte[] getStoredObject(String bucket, String key) {
    String fullKey = bucket + "/" + key;
    byte[] content = objects.get(fullKey);
    if (content == null) {
      throw new NoSuchElementException("No object at " + fullKey);
    }
    return content;
  }

  public boolean hasObject(String bucket, String key) {
    return objects.containsKey(bucket + "/" + key);
  }

  public void reset() {
    objects.clear();
    putRequests.clear();
    putExceptionRules.clear();
    getExceptionRules.clear();
  }

  private void validateBucket(String bucket) {
    if (!knownBuckets.contains(bucket)) {
      throw NoSuchBucketException.builder()
          .message("The specified bucket does not exist: " + bucket)
          .build();
    }
  }

  private record ExceptionRule<T>(
      Predicate<T> condition, Supplier<? extends RuntimeException> exception) {}
}
