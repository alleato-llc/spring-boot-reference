package com.alleato.ecommerce.ordering.repository;

import com.alleato.ecommerce.ordering.models.Order;
import com.alleato.ecommerce.ordering.models.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

  @Query("SELECT o FROM Order o LEFT JOIN FETCH o.lineItems WHERE o.id = :id")
  Optional<Order> findByIdWithLineItems(Long id);

  List<Order> findByCustomerId(String customerId);

  List<Order> findByStatus(OrderStatus status);
}
