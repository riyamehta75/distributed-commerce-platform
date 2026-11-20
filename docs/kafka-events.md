# Kafka Events

Required topics are listed in `infrastructure/kafka/topics.txt`.

Every event has `eventId`, `eventType`, `aggregateId`, `correlationId`, `timestamp`, `version`, and `payload`. Consumers use explicit consumer groups and deduplicate event IDs. Production deployments should use retry topics, dead-letter topics, partitioning by aggregate ID, schema registry compatibility checks, and bounded exponential backoff.
