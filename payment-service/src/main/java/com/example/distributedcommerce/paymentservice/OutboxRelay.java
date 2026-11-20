package com.example.distributedcommerce.paymentservice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${commerce.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEvent> events = jdbc.query(
                "SELECT id,aggregate_id,event_type,payload FROM outbox WHERE published=false ORDER BY created_at LIMIT 100",
                (rs, row) -> new OutboxEvent(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("aggregate_id")), rs.getString("event_type"),
                        rs.getString("payload")));
        for (OutboxEvent event : events) {
            try {
                kafka.send(event.topic(), event.aggregateId().toString(), event.payload())
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing outbox event " + event.id(), ex);
            } catch (ExecutionException | TimeoutException ex) {
                throw new IllegalStateException("Could not publish outbox event " + event.id(), ex);
            }
            int updated = jdbc.update("UPDATE outbox SET published=true WHERE id=? AND published=false", event.id());
            if (updated != 1) {
                throw new IllegalStateException("Could not mark outbox event as published: " + event.id());
            }
        }
    }

    private record OutboxEvent(UUID id, UUID aggregateId, String topic, String payload) {}
}
