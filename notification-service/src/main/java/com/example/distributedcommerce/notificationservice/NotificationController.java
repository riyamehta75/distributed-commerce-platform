package com.example.distributedcommerce.notificationservice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationView send(@Valid @RequestBody NotificationRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications(id,recipient,event_type,status,payload) VALUES (?,?,?,'SENT',?)",
                id, request.recipient(), request.eventType(), request.message());
        return new NotificationView(id, request.recipient(), request.eventType(), "SENT",
                request.message(), Instant.now());
    }

    @GetMapping
    public List<NotificationView> list() {
        return jdbc.query("SELECT id,recipient,event_type,status,payload,created_at FROM notifications ORDER BY created_at DESC LIMIT 100",
                (rs, row) -> new NotificationView(UUID.fromString(rs.getString("id")), rs.getString("recipient"),
                        rs.getString("event_type"), rs.getString("status"), rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public record NotificationRequest(@NotBlank String recipient, @NotBlank @Size(max = 100) String eventType,
                                      @NotBlank String message) {}
    public record NotificationView(UUID id, String recipient, String eventType, String status,
                                  String message, Instant createdAt) {}
}
