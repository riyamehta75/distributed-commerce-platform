# Architecture

The platform uses seven independent Spring Boot applications behind an API gateway. Each service owns a PostgreSQL logical database and communicates asynchronously through Kafka for cross-service workflows. Redis is a product-cache accelerator and is not the source of inventory truth.

## Communication

Synchronous REST handles client-facing queries and commands. Kafka events propagate durable state changes between bounded contexts. No service has cross-service database credentials or foreign keys.

## Trade-offs

Microservices increase independent deployment and ownership, but add network, schema, and operational complexity. Database-per-service avoids coupling while requiring saga compensation and eventual consistency. Kafka provides durable fan-out and replay; consumers remain at least-once and business logic is deduplicated.
