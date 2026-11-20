package com.example.distributedcommerce.orderservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderEventSerializationTest {
    @Test
    void serializesNotificationEnvelopeForTheKafkaConsumer() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderController.CommerceEvent event = new OrderController.CommerceEvent(
                UUID.randomUUID(), "notification.requested", orderId, orderId, Instant.now(), 1,
                new OrderController.NotificationPayload("customer-id", "ORDER_CREATED", "Order reserved."));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(event));

        assertEquals("notification.requested", json.get("eventType").asText());
        assertEquals("customer-id", json.get("payload").get("recipient").asText());
        assertEquals("ORDER_CREATED", json.get("payload").get("eventType").asText());
    }
}
