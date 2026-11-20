# Kafka Topics and Local Access

Compose starts a single-node Kafka broker in KRaft mode. Kafka UI is available at `http://localhost:8088`. Host applications connect to `localhost:9092`; Compose applications connect to `kafka:9092`. The broker has three partitions per topic and replication factor one, suitable for local development only.

Compose creates every non-empty topic listed in `infrastructure/kafka/topics.txt` on startup. Inspect the list in Kafka UI or run:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

The topic names are reserved for future integration. The current checkout is synchronous REST and does not publish or consume these events. When event handling is added, the proposed envelope is `eventId`, `eventType`, `aggregateId`, `correlationId`, `timestamp`, `version`, and `payload`. Production consumers should use explicit consumer groups, idempotent processing, retry/dead-letter topics, schema compatibility checks, and bounded retries.
