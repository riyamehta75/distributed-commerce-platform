.PHONY: build test up down
build:
	mvn clean package -DskipTests
test:
	mvn test
up: build
	docker compose up --build
down:
	docker compose down
