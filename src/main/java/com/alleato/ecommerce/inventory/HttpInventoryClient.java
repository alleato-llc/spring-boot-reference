package com.alleato.ecommerce.inventory;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP implementation of {@link InventoryClient}.
 *
 * <p>Calls the external inventory service's REST API. Only active in production — tests use an
 * in-memory implementation instead.
 */
@Component
@Profile("!test")
public class HttpInventoryClient implements InventoryClient {

  private final RestClient restClient;

  public HttpInventoryClient(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public ReservationConfirmation reserveItems(String orderId, List<ReservationItem> items) {
    var request = new ReserveRequest(orderId, items);

    return restClient
        .post()
        .uri("/api/reservations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(ReservationConfirmation.class);
  }

  private record ReserveRequest(String orderId, List<ReservationItem> items) {}
}
