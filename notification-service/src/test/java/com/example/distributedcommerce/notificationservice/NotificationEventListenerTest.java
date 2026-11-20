package com.example.distributedcommerce.notificationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventListenerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationEventListener listener = new NotificationEventListener(
            jdbc, new ObjectMapper().findAndRegisterModules());

    @Test
    void persistsANotificationForANewEvent() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String message = eventJson();

        listener.consume(message);

        verify(jdbc).update(
                eq("INSERT INTO notifications(id,recipient,event_type,status,payload) VALUES (?,?,?,'SENT',?)"),
                any(Object[].class));
    }

    @Test
    void ignoresAnEventAlreadyRecordedInTheInbox() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        listener.consume(eventJson());

        verify(jdbc).update(
                eq("INSERT INTO processed_events(event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING"),
                any(Object[].class));
        verify(jdbc, never()).update(
                eq("INSERT INTO notifications(id,recipient,event_type,status,payload) VALUES (?,?,?,'SENT',?)"),
                any(Object[].class));
    }

    private String eventJson() throws Exception {
        CommerceEvent event = new CommerceEvent(UUID.randomUUID(), "notification.requested",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 1,
                new CommerceEvent.NotificationPayload("customer-id", "ORDER_CREATED",
                        "Your order is reserved and awaiting payment."));
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
    }
}
