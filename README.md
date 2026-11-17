# payments-ledger

A merchant payments service: payments, captures, refunds, a double-entry ledger that always balances, and signed webhooks.

> Status: project skeleton. No business logic yet.

## Stack

Java 21 · Spring Boot 4.0 · PostgreSQL 16 · Flyway · RabbitMQ · Testcontainers · GitHub Actions

## Prerequisites

- JDK 21
- Docker (for local infrastructure and integration tests)

Maven is not required; use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run locally

```bash
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

| What | Where |
|---|---|
| Health | http://localhost:8080/actuator/health |
| OpenAPI spec | http://localhost:8080/v3/api-docs |
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| RabbitMQ management | http://localhost:15672 (payments / payments) |

Postgres is published on host port **5433** (not 5432) so it doesn't clash with a locally installed Postgres.

### Configuration

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/payments` |
| `DB_USER` | `payments` |
| `DB_PASSWORD` | `payments` |

The `local` profile shows full health details and logs SQL.

## Build and test

```bash
./mvnw verify -DskipITs   # build + unit tests (no Docker needed)
./mvnw verify             # + integration tests (*IT, Testcontainers)
./mvnw spotless:apply     # format
./mvnw spotless:check     # check formatting (CI runs this)
```

Unit tests are `*Test` (Surefire); integration tests are `*IT` (Failsafe) and extend `AbstractIT`, which shares one Postgres container across all integration tests.

## Database migrations

Flyway migrations live in `src/main/resources/db/migration` and run on startup. Hibernate only validates the schema (`ddl-auto: validate`); it never changes it.

## Docs

- [CLAUDE.md](CLAUDE.md): project rules and layout
- [docs/DECISIONS.md](docs/DECISIONS.md): design decisions
- [docs/RESULTS.md](docs/RESULTS.md): measured results
