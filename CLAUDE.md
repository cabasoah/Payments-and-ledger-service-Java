# CLAUDE.md — payments-ledger

A merchant payments service: payments, captures, refunds, a double-entry ledger that always balances, and signed webhooks.

## Stack
- Java 21, Spring Boot 3.x, Maven (wrapper: `./mvnw`)
- PostgreSQL 16, Flyway migrations (`src/main/resources/db/migration`)
- RabbitMQ (outbox relay, webhook delivery, dead-letter queue)
- JUnit 5, AssertJ, Testcontainers (Postgres + RabbitMQ), k6 for load tests
- Docker / docker-compose for local; Terraform + AWS (ECS Fargate, RDS) for cloud
- GitHub Actions CI; OpenTelemetry, Prometheus, Grafana for monitoring

## Layout
```
src/main/java/com/adomako/payments/
  merchant/     merchants, API keys
  payment/      payment aggregate, state machine, controller, service
  ledger/       accounts, journal entries, balance checks
  processor/    fake card processor client + fault injection
  idempotency/  idempotency key store + filter
  outbox/       outbox table, relay to RabbitMQ
  webhook/      webhook endpoints, signing, delivery worker
  common/       Money, errors, request IDs, config
src/test/java/...  mirrors main; *Test = unit, *IT = integration (Testcontainers)
infra/terraform/   cloud infrastructure
load/              k6 scripts
docs/              DECISIONS.md, RESULTS.md, RUNBOOK.md, incidents/
```

## Commands
- Build + unit tests: `./mvnw verify -DskipITs`
- All tests (needs Docker): `./mvnw verify`
- Run locally: `docker compose up -d && ./mvnw spring-boot:run`
- Format/lint: `./mvnw spotless:apply` then `./mvnw spotless:check`

## Money rules (non-negotiable)
- Amounts are `long` minor units (cents) plus an ISO-4217 currency code. Never `double`, `float`, or `BigDecimal` arithmetic on amounts in the domain.
- Use the `Money` value type everywhere; operations on mismatched currencies throw.
- Ledger entries are append-only. No UPDATE or DELETE on ledger tables, ever. Corrections are new reversing entries.
- Every journal transaction's entries sum to zero per currency. A check enforces this.
- Refund totals can never exceed the captured amount, even under concurrent requests.

## Working rules
- Work one phase / one task at a time. Plan first; no code until the plan is approved.
- For anything touching money, retries, concurrency or security: write the tests first, stop, and let me review them before implementing.
- **Never edit, weaken, delete or @Disable a test to make it pass.** If a test looks wrong, stop and explain why.
- A task is done only when `./mvnw verify` and `spotless:check` pass. Show me the output.
- Small commits, one logical change each, conventional commit messages (`feat:`, `fix:`, `test:`, `chore:`).
- Never invent numbers. RESULTS.md only gets numbers from runs I performed. Leave placeholders if no run exists.
- When you make a design choice, add a short entry to `docs/DECISIONS.md` (context, choice, alternatives, trade-off).
- Prefer plain, boring Spring code over clever abstractions. If a line needs a comment to be understood, simplify it first.
- No real card numbers anywhere, including tests and seed data. Use fake tokens like `tok_visa_ok`.
- Don't add dependencies without saying which and why.