package com.alleato.ecommerce.ordering.support;

import com.alleato.ecommerce.ordering.support.aws.clients.TestS3Client;
import com.alleato.ecommerce.ordering.support.aws.clients.TestSnsClient;
import com.alleato.ecommerce.ordering.support.aws.clients.TestSqsClient;
import com.alleato.ecommerce.ordering.support.clients.OrderClient;
import com.alleato.ecommerce.ordering.support.clients.TestInventoryClient;
import com.alleato.ecommerce.ordering.support.clients.TestPaymentClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests. Provides: - A real Spring Boot app running on a random port -
 * Postgres connection configured via application-test.yml (start Postgres with docker-compose) -
 * Flyway migrations applied automatically on startup - Test AWS SDK clients injected into the real
 * service implementations - Test doubles for payment client and inventory client - An {@link
 * OrderClient} for typed HTTP calls to the API - Automatic reset of all test state between tests
 *
 * <p>All integration tests should extend this class.
 *
 * <p>Prerequisites: Postgres must be running before tests start. Locally: {@code docker-compose up
 * -d} CI: use a service container in your pipeline config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfiguration.class)
public abstract class BaseIntegrationTest {

  @Autowired protected OrderClient orderClient;

  @Autowired protected TestPaymentClient testPaymentClient;

  @Autowired protected TestSqsClient testSqsClient;

  @Autowired protected TestSnsClient testSnsClient;

  @Autowired protected TestS3Client testS3Client;

  @Autowired protected TestInventoryClient testInventoryClient;

  @BeforeEach
  void setUpBase() {
    testPaymentClient.reset();
    testSqsClient.reset();
    testSnsClient.reset();
    testS3Client.reset();
    testInventoryClient.reset();
  }
}
