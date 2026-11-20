package com.example.distributedcommerce.inventoryservice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {
    private final JdbcTemplate jdbc;

    public ReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID reserve(UUID productId, UUID orderId, int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        List<Reservation> existing = jdbc.query("""
                SELECT id,product_id,quantity,status FROM inventory_reservation
                WHERE order_id=? AND product_id=?
                """, (rs, row) -> new Reservation(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("product_id")), rs.getInt("quantity"), rs.getString("status")),
                orderId, productId);
        if (!existing.isEmpty()) {
            Reservation reservation = existing.getFirst();
            if (reservation.quantity() == quantity && reservation.status().equals("RESERVED")) return reservation.id();
            throw new IllegalStateException("RESERVATION_ALREADY_PROCESSED");
        }
        int updated = jdbc.update("""
                UPDATE stock SET available_quantity=available_quantity-?,
                    reserved_quantity=reserved_quantity+?, version=version+1, updated_at=now()
                WHERE product_id=? AND available_quantity>=?
                """, quantity, quantity, productId, quantity);
        if (updated != 1) throw new IllegalStateException("INSUFFICIENT_STOCK");
        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_reservation(id,order_id,product_id,quantity,status,expires_at)
                VALUES (?,?,?,?,'RESERVED',?)
                """, reservationId, orderId, productId, quantity,
                Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES)));
        return reservationId;
    }

    @Transactional
    public void confirmOrder(UUID orderId) {
        List<Reservation> reservations = jdbc.query("""
                SELECT id,product_id,quantity,status FROM inventory_reservation
                WHERE order_id=? AND status='RESERVED' FOR UPDATE
                """, (rs, row) -> new Reservation(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("product_id")), rs.getInt("quantity"), rs.getString("status")), orderId);
        for (Reservation reservation : reservations) {
            int updated = jdbc.update("""
                    UPDATE stock SET reserved_quantity=reserved_quantity-?, version=version+1, updated_at=now()
                    WHERE product_id=? AND reserved_quantity>=?
                    """, reservation.quantity(), reservation.productId(), reservation.quantity());
            if (updated != 1) throw new IllegalStateException("Reservation stock invariant violated");
            jdbc.update("UPDATE inventory_reservation SET status='CONFIRMED' WHERE id=?",
                    reservation.id());
        }
    }

    @Transactional
    public void releaseOrder(UUID orderId) {
        List<Reservation> reservations = jdbc.query("""
                SELECT id,product_id,quantity,status FROM inventory_reservation
                WHERE order_id=? AND status='RESERVED' FOR UPDATE
                """, (rs, row) -> new Reservation(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("product_id")), rs.getInt("quantity"), rs.getString("status")), orderId);
        for (Reservation reservation : reservations) {
            int updated = jdbc.update("""
                    UPDATE stock SET available_quantity=available_quantity+?,
                        reserved_quantity=reserved_quantity-?, version=version+1, updated_at=now()
                    WHERE product_id=? AND reserved_quantity>=?
                    """, reservation.quantity(), reservation.quantity(), reservation.productId(), reservation.quantity());
            if (updated != 1) throw new IllegalStateException("Reservation stock invariant violated");
            jdbc.update("UPDATE inventory_reservation SET status='RELEASED' WHERE id=?",
                    reservation.id());
        }
    }

    private record Reservation(UUID id, UUID productId, int quantity, String status) {}
}
