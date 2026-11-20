package com.example.distributedcommerce.inventoryservice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final String internalApiToken;

    public InventoryController(JdbcTemplate jdbc, ReservationService reservations,
                               @Value("${internal.api-token}") String internalApiToken) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.internalApiToken = internalApiToken;
    }

    @GetMapping("/{productId}")
    public StockView get(@PathVariable UUID productId) {
        return jdbc.query("SELECT product_id,available_quantity,reserved_quantity FROM stock WHERE product_id=?",
                (rs, row) -> new StockView(UUID.fromString(rs.getString("product_id")),
                        rs.getInt("available_quantity"), rs.getInt("reserved_quantity")), productId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found"));
    }

    @PutMapping("/{productId}")
    public StockView set(@PathVariable UUID productId, @Valid @RequestBody SetStockRequest request) {
        int changed = jdbc.update("UPDATE stock SET available_quantity=?,updated_at=now(),version=version+1 WHERE product_id=? AND reserved_quantity<=?",
                request.quantity(), productId, request.quantity());
        if (changed == 0) {
            if (jdbc.queryForObject("SELECT count(*) FROM stock WHERE product_id=?", Integer.class, productId) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock cannot be set below existing reservations");
            }
            jdbc.update("INSERT INTO stock(product_id,available_quantity) VALUES (?,?) ON CONFLICT(product_id) DO NOTHING",
                    productId, request.quantity());
        }
        return get(productId);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView reserve(@RequestHeader("X-Internal-Token") String token,
                                   @Valid @RequestBody ReserveRequest request) {
        requireInternalToken(token);
        try {
            UUID id = reservations.reserve(request.productId(), request.orderId(), request.quantity());
            return new ReservationView(id, request.orderId(), request.productId(), request.quantity(), "RESERVED");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @PostMapping("/reservations/{orderId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID orderId, @RequestHeader("X-Internal-Token") String token) {
        requireInternalToken(token);
        reservations.releaseOrder(orderId);
    }

    @PostMapping("/reservations/{orderId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable UUID orderId, @RequestHeader("X-Internal-Token") String token) {
        requireInternalToken(token);
        reservations.confirmOrder(orderId);
    }

    private void requireInternalToken(String supplied) {
        if (!MessageDigest.isEqual(internalApiToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal service token");
        }
    }

    public record SetStockRequest(@Min(0) int quantity) {}
    public record ReserveRequest(@NotNull UUID productId, @NotNull UUID orderId, @Min(1) int quantity) {}
    public record StockView(UUID productId, int available, int reserved) {}
    public record ReservationView(UUID id, UUID orderId, UUID productId, int quantity, String status) {}
}
