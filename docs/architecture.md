# Architecture

The runnable demo consists of a Next.js storefront, an API gateway, and six Spring Boot business services. Each business service owns a PostgreSQL logical database. PostgreSQL, Redis, Kafka, Prometheus, Grafana, Kafka UI, and the storefront run in Docker Compose.

## Implemented request flow

The browser calls the gateway with JSON and a JWT for protected routes. The gateway routes requests to the user, product, inventory, order, payment, or notification service. Order creation reads product prices over REST, creates an order, and reserves inventory over REST. The simulated payment endpoint confirms inventory and the order through internal REST calls. PostgreSQL protects stock counts with conditional updates.

## Local platform services

- PostgreSQL is available on host port 5432. The six logical databases are initialized on first startup.
- Kafka is available on host port 9092. Compose creates the topic names from `infrastructure/kafka/topics.txt`, and Kafka UI is available on port 8088.
- Redis is available on host port 6379. It is not used by the current catalog flow.
- Prometheus scrapes service actuator metrics on port 9090. Grafana, with a provisioned Prometheus source and dashboard, is available on port 3001.

Order and payment services relay outbox notifications through Kafka to the notification service. The notification service deduplicates messages by event ID before storing notification history. Inventory reservation and payment confirmation remain synchronous REST calls. See `docs/kafka-events.md` for the event envelope and topic details.

## Trade-offs

This is a local development/demo system. The services have independent schemas and migrations, but share a PostgreSQL server and Compose network. Payments and notifications are simulations. Production use would need transactionally coupled outbox writes, retry and dead-letter handling, stronger secrets management, access controls, TLS, and operational policies.
