# System Design

## Implemented checkout

Order creation synchronously reads current catalog prices, persists an order, and asks inventory to reserve each line. If a reservation fails, the order service attempts to release any reservation and marks the order cancelled. A successful reservation moves the order to `AWAITING_PAYMENT`. The payment service records an idempotent simulated payment and calls the order service to confirm the inventory reservation and order. These calls use REST and internal service credentials.

Inventory uses a conditional PostgreSQL update to prevent available stock from going below zero under concurrent reservations. Payments do not charge a real provider, and refunds are simulated.

## Kafka and future saga work

Compose starts Kafka and creates the topics listed in `infrastructure/kafka/topics.txt`. At this stage, business services do not publish or consume events; outbox and processed-event tables are not an active delivery pipeline. The topics and event envelope in `docs/kafka-events.md` are a starting contract for a future asynchronous saga.

A production event flow should write business state and outbox records in one database transaction, publish with retries, deduplicate deliveries by event ID, partition by aggregate ID, and define compensation and dead-letter behavior. Those mechanisms are not implemented in this demo.
