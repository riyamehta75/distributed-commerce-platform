# Setup and Execution Guide

1. Install Java 21, Maven 3.9+, and Docker Compose.
2. Copy `.env.example` to `.env`.
3. Run `mvn clean package -DskipTests`.
4. Run `docker compose up --build`.
5. Access gateway health at `http://localhost:8080/actuator/health`.
6. Access Swagger or OpenAPI through service actuator/documentation paths once Springdoc is added.
7. Access Prometheus at `http://localhost:9090` and Grafana at `http://localhost:3000`.

The exact commands are summarized in README.md.
