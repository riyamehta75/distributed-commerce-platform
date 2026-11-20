CREATE TABLE notifications (id UUID PRIMARY KEY, recipient VARCHAR(255) NOT NULL, event_type VARCHAR(100) NOT NULL, status VARCHAR(32) NOT NULL, payload TEXT, created_at TIMESTAMP NOT NULL DEFAULT now());
CREATE TABLE processed_events (event_id UUID PRIMARY KEY, received_at TIMESTAMP NOT NULL DEFAULT now());
