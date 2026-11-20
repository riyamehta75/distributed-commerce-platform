package com.example.distributedcommerce.paymentservice;

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
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final JdbcTemplate jdbc;
    private final RestClient rest;
    private final String orderServiceUrl;
    private final String internalApiToken;

    public PaymentController(JdbcTemplate jdbc, RestClient.Builder builder,
                             @Value("${services.order-url:http://order-service:8084}") String orderServiceUrl,
                             @Value("${internal.api-token}") String internalApiToken) {
        this.jdbc = jdbc;
        this.rest = builder.build();
        this.orderServiceUrl = orderServiceUrl;
        this.internalApiToken = internalApiToken;
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
        requireOwner(getOrder(existing.orderId()), authenticatedUser);
        int changed = jdbc.update("UPDATE payments SET status='REFUNDED',updated_at=now() WHERE id=? AND status='COMPLETED'", id);
        if (changed == 0) {
            PaymentView payment = find(id);
            if (payment.status().equals("REFUNDED")) return payment;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed payments can be refunded");
        }
        PaymentView payment = find(id);
        jdbc.update("INSERT INTO payment_refunds(id,payment_id,amount,status) VALUES (?,?,?,'COMPLETED')",
                UUID.randomUUID(), id, payment.amount());
        return payment;
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
}
