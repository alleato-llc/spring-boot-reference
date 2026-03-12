package com.alleato.ecommerce.ordering.controller;

import com.alleato.ecommerce.ordering.models.CreateOrderRequest;
import com.alleato.ecommerce.ordering.models.OrderResponse;
import com.alleato.ecommerce.ordering.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
    var order = orderService.createOrder(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
    var order = orderService.getOrder(id);
    return ResponseEntity.ok(OrderResponse.from(order));
  }
}
