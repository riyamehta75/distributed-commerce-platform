# Kafka Topics and Local Access

Compose starts a single-node Kafka broker in KRaft mode. Kafka UI is available at `http://localhost:8088`. Host applications connect to `localhost:9092`; Compose applications connect to `kafka:9092`. The broker has three partitions per topic and replication factor one, suitable for local development only.

Compose creates every non-empty topic listed in `infrastructure/kafka/topics.txt` on startup. Inspect the list in Kafka UI or run:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

## Implemented notification events

Order and payment services write notification envelopes to their existing `outbox` tables. Their scheduled relays publish pending records to `notification.requested`, using the order or payment ID as the Kafka key. Events include:

- `ORDER_CREATED`, `ORDER_CONFIRMED`, and `ORDER_CANCELLED`
- `PAYMENT_COMPLETED` and `PAYMENT_REFUNDED`

The JSON envelope contains `eventId`, `eventType`, `aggregateId`, `correlationId`, `timestamp`, `version`, and a `payload` with `recipient` (the customer UUID), `eventType`, and `message`. Amounts included in messages are INR. The notification service uses the `notification-service` consumer group, persists event IDs in `processed_events`, and stores a simulated notification. The inbox and notification inserts are transactional, so repeated delivery of an event ID is ignored.

The checkout still uses synchronous REST for product prices, inventory reservations, and order confirmation. Other listed topics are reserved for future event flows. Production consumers should add dead-letter handling, schema compatibility checks, and bounded retry policies.
