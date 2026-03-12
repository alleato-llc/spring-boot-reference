package com.alleato.ecommerce.ordering.support.clients;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleato.ecommerce.ordering.models.CreateOrderRequest;
import com.alleato.ecommerce.ordering.models.OrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Typed HTTP client for the Order API, used exclusively in integration tests.
 *
 * <p>Methods without an {@link HttpStatus} parameter assert the expected default status (201 for
 * create, 200 for get). Use the overloaded variant to assert a different status.
 *
 * <p>On error status codes, throws {@link ApiException} with the status and response body for test
 * assertion.
 */
public class OrderClient {

  private final RestClient restClient;

  public OrderClient(RestClient restClient) {
    this.restClient = restClient;
  }

  /** POST an order, assert 201 CREATED, return the response body. */
  public OrderResponse createOrder(CreateOrderRequest request) {
    return createOrder(request, HttpStatus.CREATED);
  }

  /** POST an order, assert the given status, return the response body. */
  public OrderResponse createOrder(CreateOrderRequest request, HttpStatus expectedStatus) {
    var response =
        restClient
            .post()
            .uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange(
                (req, res) -> {
                  HttpStatusCode status = res.getStatusCode();
                  if (status.isError()) {
                    String body = new String(res.getBody().readAllBytes());
                    throw new ApiException(HttpStatus.valueOf(status.value()), body);
                  }
                  assertThat(status).isEqualTo(expectedStatus);
                  return res.bodyTo(OrderResponse.class);
                });
    return response;
  }

  /** GET an order by ID, assert 200 OK, return the response body. */
  public OrderResponse getOrder(Long id) {
    return getOrder(id, HttpStatus.OK);
  }

  /** GET an order by ID, assert the given status, return the response body. */
  public OrderResponse getOrder(Long id, HttpStatus expectedStatus) {
    var response =
        restClient
            .get()
            .uri("/api/orders/{id}", id)
            .exchange(
                (req, res) -> {
                  HttpStatusCode status = res.getStatusCode();
                  if (status.isError()) {
                    String body = new String(res.getBody().readAllBytes());
                    throw new ApiException(HttpStatus.valueOf(status.value()), body);
                  }
                  assertThat(status).isEqualTo(expectedStatus);
                  return res.bodyTo(OrderResponse.class);
                });
    return response;
  }

  /**
   * Thrown when the API returns an error status code. Carries the HTTP status and response body for
   * test assertion.
   */
  public static class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String body;

    public ApiException(HttpStatus status, String body) {
      super("API returned %d: %s".formatted(status.value(), body));
      this.status = status;
      this.body = body;
    }

    public HttpStatus status() {
      return status;
    }

    public String body() {
      return body;
    }
  }
}
