package org.dbplatform.examples.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class OrdersController {

    private final OrdersRepository repository;

    public OrdersController(OrdersRepository repository) {
        this.repository = repository;
    }

    /** GET /orders?limit=20 */
    @GetMapping("/orders")
    public List<OrderView> listOrders(@RequestParam(defaultValue = "20") int limit) {
        return repository.listOrders(Math.max(1, Math.min(limit, 500)));
    }

    /** GET /orders/{id} */
    @GetMapping("/orders/{id}")
    public OrderView getOrder(@PathVariable long id) {
        return repository.findOrder(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found"));
    }

    /** POST /orders {"customerId":1,"productId":2,"qty":3} → calls ORDER_PKG.PLACE_ORDER */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> placeOrder(@RequestBody PlaceOrderRequest request) {
        if (request == null || request.qty() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qty must be positive");
        }
        long orderId = repository.placeOrder(request.customerId(), request.productId(), request.qty());
        OrderView order = repository.findOrder(orderId).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "orderId", orderId,
                "order", order == null ? Map.of() : order));
    }

    /** GET /customers/{id}/tier → function call GET_CUSTOMER_TIER */
    @GetMapping("/customers/{id}/tier")
    public Map<String, Object> customerTier(@PathVariable long id) {
        return Map.of("customerId", id, "tier", repository.customerTier(id));
    }

    /** GET /customers/{id}/orders → ref cursor OUT parameter of GET_ORDERS_FOR_CUSTOMER */
    @GetMapping("/customers/{id}/orders")
    public Map<String, Object> customerOrders(@PathVariable long id) {
        OrdersRepository.CursorResult result = repository.ordersForCustomer(id);
        return Map.of("customerId", id, "source", result.source(), "orders", result.orders());
    }

    public record PlaceOrderRequest(long customerId, long productId, int qty) {
    }
}
