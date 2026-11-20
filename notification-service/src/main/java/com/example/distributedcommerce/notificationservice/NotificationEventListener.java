package com.example.distributedcommerce.notificationservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class NotificationEventListener {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationEventListener(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${commerce.kafka.notification-topic:notification.requested}",
            groupId = "${spring.kafka.consumer.group-id:notification-service}")
    @Transactional
    public void consume(String message) {
        CommerceEvent event;
        try {
            event = objectMapper.readValue(message, CommerceEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid notification event JSON", ex);
        }
        if (event.eventId() == null || event.payload() == null
                || event.payload().recipient() == null || event.payload().eventType() == null
                || event.payload().message() == null) {
            throw new IllegalArgumentException("Notification event is missing required fields");
        }
        int inserted = jdbc.update(
                "INSERT INTO processed_events(event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId());
        if (inserted == 0) return;

        jdbc.update("INSERT INTO notifications(id,recipient,event_type,status,payload) VALUES (?,?,?,'SENT',?)",
                UUID.randomUUID(), event.payload().recipient(), event.payload().eventType(),
                event.payload().message());
    }
}
