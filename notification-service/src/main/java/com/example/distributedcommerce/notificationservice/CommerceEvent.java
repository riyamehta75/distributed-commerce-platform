package com.example.distributedcommerce.notificationservice;

import java.time.Instant;
import java.util.UUID;

public record CommerceEvent(UUID eventId, String eventType, UUID aggregateId, UUID correlationId,
                            Instant timestamp, int version, NotificationPayload payload) {
    public record NotificationPayload(String recipient, String eventType, String message) {}
}
