package com.example.distributedcommerce.orderservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final JdbcTemplate jdbc;
    private final RestClient rest;
    private final String productServiceUrl;
    private final String inventoryServiceUrl;
    private final String internalApiToken;
    private final ObjectMapper objectMapper;

    public OrderController(JdbcTemplate jdbc, RestClient.Builder restClientBuilder,
                           @Value("${services.product-url:http://product-service:8082}") String productServiceUrl,
                           @Value("${services.inventory-url:http://inventory-service:8083}") String inventoryServiceUrl,
                           @Value("${internal.api-token}") String internalApiToken,
                           ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rest = restClientBuilder.build();
        this.productServiceUrl = productServiceUrl;
        this.inventoryServiceUrl = inventoryServiceUrl;
        this.internalApiToken = internalApiToken;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(@RequestHeader("X-User-Id") UUID authenticatedUser,
                            @Valid @RequestBody CreateOrderRequest request) {
        if (!authenticatedUser.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot place an order for another user");
        }
        long uniqueProducts = request.items().stream().map(ItemRequest::productId).distinct().count();
        if (uniqueProducts != request.items().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order can contain a product only once");
        }
        UUID orderId = UUID.randomUUID();
        List<PricedItem> items;
        try {
            items = request.items().stream().map(item -> {
                Product product = rest.get().uri(productServiceUrl + "/api/products/{id}", item.productId())
                        .retrieve().body(Product.class);
                if (product == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product service returned no product");
                return new PricedItem(item.productId(), item.quantity(), product.price(),
                        product.price().multiply(BigDecimal.valueOf(item.quantity())));
            }).toList();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order contains an unknown or inactive product");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product service request failed");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product service is unavailable");
        }
        BigDecimal total = items.stream().map(PricedItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("INSERT INTO orders(id,user_id,status,total_amount) VALUES (?,?,?,?)",
                orderId, request.userId(), "PENDING", total);
        for (PricedItem item : items) {
            jdbc.update("INSERT INTO order_items(id,order_id,product_id,quantity,unit_price) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), orderId, item.productId(), item.quantity(), item.unitPrice());
        }

        try {
            for (PricedItem item : items) {
                rest.post().uri(inventoryServiceUrl + "/api/inventory/reservations")
                        .header("X-Internal-Token", internalApiToken)
                        .body(new ReservationRequest(orderId, item.productId(), item.quantity()))
                        .retrieve().toBodilessEntity();
            }
        } catch (RestClientException ex) {
            try {
                releaseQuietly(orderId);
            } catch (ResponseStatusException releaseError) {
                jdbc.update("UPDATE orders SET status='COMPENSATION_REQUIRED',updated_at=now() WHERE id=?", orderId);
                throw releaseError;
            }
            jdbc.update("UPDATE orders SET status='CANCELLED',updated_at=now() WHERE id=?", orderId);
            if (ex instanceof RestClientResponseException response && response.getStatusCode().value() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient inventory; order was cancelled");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Inventory service request failed; order was cancelled");
        }
        jdbc.update("UPDATE orders SET status='AWAITING_PAYMENT',updated_at=now() WHERE id=?", orderId);
        enqueueNotification(orderId, request.userId(), "ORDER_CREATED",
                "Your order is reserved and awaiting payment.");
        return loadOrder(orderId);
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable UUID id,
                         @RequestHeader(value = "X-User-Id", required = false) UUID authenticatedUser,
                         @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        OrderView order = loadOrder(id);
        if (internalToken != null) {
            requireInternalToken(internalToken);
        } else if (authenticatedUser == null || !authenticatedUser.equals(order.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another user's order");
        }
        return order;
    }

    private OrderView loadOrder(UUID id) {
        var orders = jdbc.query("SELECT id,user_id,status,total_amount,created_at FROM orders WHERE id=?",
                (rs, row) -> new OrderHeader(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")), rs.getString("status"),
                        rs.getBigDecimal("total_amount"), rs.getTimestamp("created_at").toInstant()), id);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        OrderHeader order = orders.getFirst();
        List<OrderItemView> items = jdbc.query("SELECT product_id,quantity,unit_price FROM order_items WHERE order_id=?",
                (rs, row) -> new OrderItemView(UUID.fromString(rs.getString("product_id")), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price")), id);
        return new OrderView(order.id(), order.userId(), order.status(), order.total(), order.createdAt(), items);
    }

    @GetMapping
    public List<OrderView> history(@RequestParam UUID userId, @RequestHeader("X-User-Id") UUID authenticatedUser) {
        if (!authenticatedUser.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another user's orders");
        }
        List<UUID> ids = jdbc.query("SELECT id FROM orders WHERE user_id=? ORDER BY created_at DESC",
                (rs, row) -> UUID.fromString(rs.getString("id")), userId);
        return ids.stream().map(this::loadOrder).toList();
    }

    @PostMapping("/{id}/confirm")
    public OrderView confirm(@PathVariable UUID id, @RequestHeader("X-Internal-Token") String token) {
        requireInternalToken(token);
        OrderView before = loadOrder(id);
        if (before.status().equals("CONFIRMED")) {
            enqueueNotification(id, before.userId(), "ORDER_CONFIRMED", "Your order is confirmed.");
            return before;
        }
        if (!before.status().equals("AWAITING_PAYMENT")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
        }
        rest.post().uri(inventoryServiceUrl + "/api/inventory/reservations/{orderId}/confirm", id)
                .header("X-Internal-Token", internalApiToken)
                .retrieve().toBodilessEntity();
        int changed = jdbc.update("UPDATE orders SET status='CONFIRMED',updated_at=now() WHERE id=? AND status='AWAITING_PAYMENT'", id);
        if (changed == 0) {
            OrderView current = loadOrder(id);
            if (!current.status().equals("CONFIRMED")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
            }
            enqueueNotification(id, current.userId(), "ORDER_CONFIRMED", "Your order is confirmed.");
            return current;
        }
        enqueueNotification(id, before.userId(), "ORDER_CONFIRMED", "Your order is confirmed.");
        return loadOrder(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID authenticatedUser) {
        OrderView order = loadOrder(id);
        if (!authenticatedUser.equals(order.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot cancel another user's order");
        }
        if (order.status().equals("CANCELLED")) {
            enqueueNotification(id, order.userId(), "ORDER_CANCELLED", "Your order was cancelled.");
            return order;
        }
        if (!order.status().equals("PENDING") && !order.status().equals("AWAITING_PAYMENT")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only unpaid orders can be cancelled");
        }
        releaseQuietly(id);
        int changed = jdbc.update("UPDATE orders SET status='CANCELLED',updated_at=now() WHERE id=? AND status IN ('PENDING','AWAITING_PAYMENT')", id);
        if (changed == 0) {
            OrderView current = loadOrder(id);
            if (current.status().equals("CANCELLED")) {
                enqueueNotification(id, current.userId(), "ORDER_CANCELLED", "Your order was cancelled.");
                return current;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only unpaid orders can be cancelled");
        }
        enqueueNotification(id, order.userId(), "ORDER_CANCELLED", "Your order was cancelled.");
        return loadOrder(id);
    }

    private void enqueueNotification(UUID orderId, UUID userId, String eventType, String message) {
        UUID eventId = UUID.nameUUIDFromBytes((eventType + ":" + orderId).getBytes(StandardCharsets.UTF_8));
        CommerceEvent event = new CommerceEvent(eventId, "notification.requested", orderId, orderId,
                Instant.now(), 1, new NotificationPayload(userId.toString(), eventType, message));
        try {
            jdbc.update("INSERT INTO outbox(id,aggregate_id,event_type,payload) VALUES (?,?,?,?) ON CONFLICT (id) DO NOTHING",
                    eventId, orderId, "notification.requested", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize order notification event", ex);
        }
    }

    private void releaseQuietly(UUID orderId) {
        try {
            rest.post().uri(inventoryServiceUrl + "/api/inventory/reservations/{orderId}/release", orderId)
                    .header("X-Internal-Token", internalApiToken)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not release inventory for order " + orderId);
        }
    }

    private void requireInternalToken(String supplied) {
        if (!MessageDigest.isEqual(internalApiToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal service token");
        }
    }

    public record CreateOrderRequest(@NotNull UUID userId, @NotEmpty List<@Valid ItemRequest> items) {}
    public record ItemRequest(@NotNull UUID productId, @Min(1) int quantity) {}
    public record OrderView(UUID id, UUID userId, String status, BigDecimal total, java.time.Instant createdAt,
                            List<OrderItemView> items) {}
    public record OrderItemView(UUID productId, int quantity, BigDecimal unitPrice) {}
    private record Product(UUID id, String name, String description, BigDecimal price) {}
    private record PricedItem(UUID productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
    private record ReservationRequest(UUID orderId, UUID productId, int quantity) {}
    private record OrderHeader(UUID id, UUID userId, String status, BigDecimal total, java.time.Instant createdAt) {}
    public record CommerceEvent(UUID eventId, String eventType, UUID aggregateId, UUID correlationId,
                                Instant timestamp, int version, NotificationPayload payload) {}
    public record NotificationPayload(String recipient, String eventType, String message) {}
}
