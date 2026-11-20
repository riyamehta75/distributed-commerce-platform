# System Design

## Implemented checkout

Order creation synchronously reads current catalog prices, persists an order, and asks inventory to reserve each line. If a reservation fails, the order service attempts to release any reservation and marks the order cancelled. A successful reservation moves the order to `AWAITING_PAYMENT`. The payment service records an idempotent simulated payment and calls the order service to confirm the inventory reservation and order. These calls use REST and internal service credentials.

Inventory uses a conditional PostgreSQL update to prevent available stock from going below zero under concurrent reservations. Payments do not charge a real provider, and refunds are simulated.

## Kafka notification flow

Order and payment services write notification events to their existing outbox tables. A scheduled relay publishes pending records to the `notification.requested` Kafka topic, keyed by aggregate ID. The notification service consumes the topic, inserts each event ID into `processed_events`, and records the simulated notification in its own database. The inbox insert and notification record share a database transaction, so Kafka redelivery does not create duplicate notifications. Kafka producer acknowledgements are required before an outbox record is marked published; a publish failure leaves it available for retry.

Inventory reservation and payment confirmation remain synchronous REST calls. This demo does not send email, implement dead-letter handling, or provide production-grade saga compensation and retry policies. Kafka topics and the envelope format are documented in `docs/kafka-events.md`.
