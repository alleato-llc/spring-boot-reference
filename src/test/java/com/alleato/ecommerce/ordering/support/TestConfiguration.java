package com.alleato.ecommerce.ordering.support;

import com.alleato.ecommerce.inventory.InventoryClient;
import com.alleato.ecommerce.ordering.support.aws.clients.TestS3Client;
import com.alleato.ecommerce.ordering.support.aws.clients.TestSnsClient;
import com.alleato.ecommerce.ordering.support.aws.clients.TestSqsClient;
import com.alleato.ecommerce.ordering.support.clients.OrderClient;
import com.alleato.ecommerce.ordering.support.clients.TestInventoryClient;
import com.alleato.ecommerce.ordering.support.clients.TestPaymentClient;
import com.alleato.ecommerce.payment.PaymentClient;
import java.util.Set;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Test configuration that provides:
 *
 * <p><b>AWS SDK test clients</b> — test implementations of {@link SqsClient}, {@link SnsClient},
 * and {@link S3Client} that are injected into the real service implementations
 * (SqsFulfillmentClient, SnsNotificationClient, S3DocumentClient). This means the real service code
 * is exercised in tests, but the actual AWS API calls are intercepted, validated, and recorded by
 * the test clients.
 *
 * <p><b>Payment client test double</b> — Stripe has no AWS SDK dependency, so {@link
 * TestPaymentClient} replaces the client interface directly.
 */
@org.springframework.boot.test.context.TestConfiguration
public class TestConfiguration {

  // --- REST client for test API calls ---

  @Bean
  @Lazy
  public RestClient testRestClient(@LocalServerPort int port) {
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }

  @Bean
  @Lazy
  public OrderClient orderClient(RestClient testRestClient) {
    return new OrderClient(testRestClient);
  }

  // --- AWS SDK test clients ---

  @Bean
  public TestSqsClient testSqsClient() {
    return new TestSqsClient(
        Set.of("https://sqs.us-east-1.amazonaws.com/123456789/order-fulfillment.fifo"));
  }

  @Bean
  public SqsClient sqsClient(TestSqsClient testImpl) {
    return testImpl;
  }

  @Bean
  public TestSnsClient testSnsClient() {
    return new TestSnsClient(Set.of("arn:aws:sns:us-east-1:123456789:order-events"));
  }

  @Bean
  public SnsClient snsClient(TestSnsClient testImpl) {
    return testImpl;
  }

  @Bean
  public TestS3Client testS3Client() {
    return new TestS3Client(Set.of("order-invoices"));
  }

  @Bean
  public S3Client s3Client(TestS3Client testImpl) {
    return testImpl;
  }

  // --- Payment client (non-AWS, uses client-level test double) ---

  @Bean
  public TestPaymentClient testPaymentClient() {
    return new TestPaymentClient();
  }

  @Bean
  public PaymentClient paymentClient(TestPaymentClient testImpl) {
    return testImpl;
  }

  // --- Inventory client (external API, uses interface-level in-memory test double) ---

  @Bean
  public TestInventoryClient testInventoryClient() {
    return new TestInventoryClient();
  }

  @Bean
  public InventoryClient inventoryClient(TestInventoryClient testImpl) {
    return testImpl;
  }
}
