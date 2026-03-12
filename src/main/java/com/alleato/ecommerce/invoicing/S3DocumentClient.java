package com.alleato.ecommerce.invoicing;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * AWS S3 implementation of {@link DocumentClient}.
 *
 * <p>Stores and retrieves documents from S3. In tests, the injected {@link S3Client} is a test
 * implementation that stores objects in-memory and validates bucket names.
 */
@Component
public class S3DocumentClient implements DocumentClient {

  private final S3Client s3Client;

  public S3DocumentClient(S3Client s3Client) {
    this.s3Client = s3Client;
  }

  @Override
  public void store(String bucket, String key, byte[] content, String contentType) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength((long) content.length)
            .build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public byte[] retrieve(String bucket, String key) {
    ResponseInputStream<GetObjectResponse> response =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    try {
      return response.readAllBytes();
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to read S3 object: " + bucket + "/" + key, e);
    }
  }
}
