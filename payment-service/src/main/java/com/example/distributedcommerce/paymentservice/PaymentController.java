package com.example.distributedcommerce.paymentservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final JdbcTemplate jdbc;
    private final RestClient rest;
    private final String orderServiceUrl;
    private final String internalApiToken;
    private final ObjectMapper objectMapper;

    public PaymentController(JdbcTemplate jdbc, RestClient.Builder builder,
                             @Value("${services.order-url:http://order-service:8084}") String orderServiceUrl,
                             @Value("${internal.api-token}") String internalApiToken,
                             ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rest = builder.build();
        this.orderServiceUrl = orderServiceUrl;
        this.internalApiToken = internalApiToken;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView pay(@RequestHeader("X-User-Id") UUID authenticatedUser,
                           @Valid @RequestBody PaymentRequest request) {
        OrderSummary order = getOrder(request.orderId());
        requireOwner(order, authenticatedUser);
        var existing = jdbc.query("SELECT id,order_id,idempotency_key,amount,status,created_at FROM payments WHERE idempotency_key=?",
                (rs, row) -> new PaymentView(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("order_id")), rs.getString("idempotency_key"),
                        rs.getBigDecimal("amount"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                request.idempotencyKey());
        PaymentView payment;
        if (!existing.isEmpty()) {
            payment = existing.getFirst();
            if (!payment.orderId().equals(request.orderId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for another order");
            }
        } else {
            if (!order.status().equals("AWAITING_PAYMENT")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not awaiting payment");
            }
            UUID paymentId = UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO payments(id,order_id,idempotency_key,amount,status,provider_reference) VALUES (?,?,?,?,?,?)",
                        paymentId, request.orderId(), request.idempotencyKey(), order.total(), "COMPLETED",
                        "SIM-" + UUID.randomUUID());
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                return pay(authenticatedUser, request);
            }
            payment = find(paymentId);
        }

        if (payment.status().equals("COMPLETED")) {
            try {
                rest.post().uri(orderServiceUrl + "/api/orders/{id}/confirm", request.orderId())
                        .header("X-Internal-Token", internalApiToken)
                        .retrieve().toBodilessEntity();
            } catch (RestClientException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Payment completed but order confirmation is pending; retry with the same idempotency key");
            }
            enqueueNotification(payment, order.userId(), "PAYMENT_COMPLETED",
                    "Payment of INR " + payment.amount().toPlainString() + " completed.");
        }
        return payment;
    }

    @GetMapping("/{id}")
    public PaymentView get(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID authenticatedUser) {
        PaymentView payment = find(id);
        requireOwner(getOrder(payment.orderId()), authenticatedUser);
        return payment;
    }

    @PostMapping("/{id}/refund")
    public PaymentView refund(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID authenticatedUser) {
        PaymentView existing = find(id);
        OrderSummary order = getOrder(existing.orderId());
        requireOwner(order, authenticatedUser);
        int changed = jdbc.update("UPDATE payments SET status='REFUNDED',updated_at=now() WHERE id=? AND status='COMPLETED'", id);
        if (changed == 0) {
            PaymentView payment = find(id);
            if (payment.status().equals("REFUNDED")) {
                enqueueNotification(payment, order.userId(), "PAYMENT_REFUNDED",
                        "Refund of INR " + payment.amount().toPlainString() + " completed.");
                return payment;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed payments can be refunded");
        }
        PaymentView payment = find(id);
        jdbc.update("INSERT INTO payment_refunds(id,payment_id,amount,status) VALUES (?,?,?,'COMPLETED')",
                UUID.randomUUID(), id, payment.amount());
        enqueueNotification(payment, order.userId(), "PAYMENT_REFUNDED",
                "Refund of INR " + payment.amount().toPlainString() + " completed.");
        return payment;
    }

    private void enqueueNotification(PaymentView payment, UUID userId, String eventType, String message) {
        UUID eventId = UUID.nameUUIDFromBytes((eventType + ":" + payment.id()).getBytes(StandardCharsets.UTF_8));
        CommerceEvent event = new CommerceEvent(eventId, "notification.requested", payment.id(),
                payment.orderId(), Instant.now(), 1,
                new NotificationPayload(userId.toString(), eventType, message));
        try {
            jdbc.update("INSERT INTO outbox(id,aggregate_id,event_type,payload) VALUES (?,?,?,?) ON CONFLICT (id) DO NOTHING",
                    eventId, payment.id(), "notification.requested", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize payment notification event", ex);
        }
    }

    private OrderSummary getOrder(UUID id) {
        try {
            OrderSummary order = rest.get().uri(orderServiceUrl + "/api/orders/{id}", id)
                    .header("X-Internal-Token", internalApiToken)
                    .retrieve().body(OrderSummary.class);
            if (order == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order service returned an empty response");
            return order;
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order service is unavailable");
        }
    }

    private void requireOwner(OrderSummary order, UUID authenticatedUser) {
        if (!order.userId().equals(authenticatedUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another user's payment");
        }
    }

    private PaymentView find(UUID id) {
        return jdbc.query("SELECT id,order_id,idempotency_key,amount,status,created_at FROM payments WHERE id=?",
                (rs, row) -> new PaymentView(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("order_id")), rs.getString("idempotency_key"),
                        rs.getBigDecimal("amount"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    public record PaymentRequest(@NotNull UUID orderId, @NotBlank String idempotencyKey) {}
    public record PaymentView(UUID id, UUID orderId, String idempotencyKey, BigDecimal amount, String status, Instant createdAt) {}
    private record OrderSummary(UUID id, UUID userId, String status, BigDecimal total, Instant createdAt, Object[] items) {}
    public record CommerceEvent(UUID eventId, String eventType, UUID aggregateId, UUID correlationId,
                                Instant timestamp, int version, NotificationPayload payload) {}
    public record NotificationPayload(String recipient, String eventType, String message) {}
}
