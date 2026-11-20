# System Design

## Saga

Order creation writes PENDING and an outbox record atomically. Inventory reserves stock and emits inventory.reserved or inventory.rejected. Payment is idempotent on an order/payment key. Success confirms the order and notifies. Failure releases inventory and cancels the order. Timeout compensates payment when captured.

## Consistency

PostgreSQL conditional updates protect available stock. Kafka event handlers insert event IDs into processed-events tables before effects. Outbox polling is retried and marks only published records. These mechanisms avoid a dual database/Kafka transaction and preserve effectively-once business outcomes.
