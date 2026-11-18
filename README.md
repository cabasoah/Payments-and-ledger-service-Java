# payments-ledger

A merchant payments service: payments, captures, refunds, and a double-entry ledger that always balances.

> **Status:** REST API for merchants, payments, capture and refunds is working, backed by an append-only ledger.
> Not built yet: authentication (API keys), the outbox/RabbitMQ relay, signed webhooks, a real card processor.

## Stack

Java 21 · Spring Boot 4.0 · PostgreSQL 16 · Flyway · RabbitMQ (not used yet) · springdoc-openapi · Testcontainers · GitHub Actions

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
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI spec | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| RabbitMQ management | http://localhost:15672 (payments / payments) |

Postgres is published on host port **5433** (not 5432) so it doesn't clash with a locally installed Postgres.

### Configuration

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/payments` |
| `DB_USER` | `payments` |
| `DB_PASSWORD` | `payments` |

The `local` profile shows full health details and logs SQL.

## API

No authentication yet: the merchant is given as `merchantId` in the create-payment body. API keys will replace it later, and the URLs will stay the same.

Amounts are **integer minor units** plus an ISO 4217 currency code: `"amount": 1050, "currency": "CAD"` is 10.50 CAD. JPY has no decimals, so `1050 JPY` is ¥1050.

| Method | Path | Body | Success |
|---|---|---|---|
| `POST` | `/v1/merchants` | `{name}` | `201` merchant |
| `POST` | `/v1/payments` | `{merchantId, amount, currency, cardToken}` | `201` payment (`AUTHORIZED` or `FAILED`) |
| `POST` | `/v1/payments/{id}/capture` | none | `200` payment (full capture only) |
| `POST` | `/v1/payments/{id}/refunds` | `{amount}` | `201` refund (partial or full) |
| `GET` | `/v1/payments/{id}` | none | `200` payment, including its refunds |

Card tokens are fake; real card numbers are never accepted.

| Token | Result |
|---|---|
| `tok_visa_ok` | Authorized |
| `tok_declined` | Declined: stored as a `FAILED` payment, still `201` |
| anything else | `422 INVALID_CARD_TOKEN` |

### Try it

```bash
MERCHANT=$(curl -s -X POST localhost:8080/v1/merchants \
  -H 'Content-Type: application/json' -d '{"name":"Acme Coffee"}' | jq -r .id)

PAYMENT=$(curl -s -X POST localhost:8080/v1/payments -H 'Content-Type: application/json' \
  -d "{\"merchantId\":\"$MERCHANT\",\"amount\":1050,\"currency\":\"CAD\",\"cardToken\":\"tok_visa_ok\"}" | jq -r .id)

curl -s -X POST localhost:8080/v1/payments/$PAYMENT/capture
curl -s -X POST localhost:8080/v1/payments/$PAYMENT/refunds \
  -H 'Content-Type: application/json' -d '{"amount":300}'
curl -s localhost:8080/v1/payments/$PAYMENT     # PARTIALLY_REFUNDED, refunded 300, 1 refund
```

### Payment lifecycle

| From | Allowed next |
|---|---|
| `PENDING` | `AUTHORIZED`, `FAILED` |
| `AUTHORIZED` | `CAPTURED`, `FAILED` |
| `CAPTURED` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `PARTIALLY_REFUNDED` (another partial refund), `REFUNDED` |
| `REFUNDED`, `FAILED` | none (terminal) |

`PaymentStatus` holds the transition table and is the only place allowed moves are defined. The database also checks that each status matches the amounts. For example, a `CAPTURED` payment can't have any refunds recorded. Refund totals can never exceed the captured amount, even under concurrent requests, because capture and refund lock the payment row.

### Errors

Every error is RFC 7807 `application/problem+json` with a machine-readable `code`:

```json
{
  "type": "/problems/refund-exceeds-captured",
  "title": "Refund exceeds captured amount",
  "status": 422,
  "detail": "Refund of 8.00 CAD exceeds the remaining refundable 7.50 CAD",
  "instance": "/v1/payments/df106970-7336-4c52-af14-c47c3865d3f5/refunds",
  "code": "REFUND_EXCEEDS_CAPTURED"
}
```

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Invalid field (includes an `errors` list), unknown currency, malformed id |
| `MALFORMED_REQUEST` | 400 | Body is missing or not valid JSON |
| `MERCHANT_NOT_FOUND` | 404 | Unknown `merchantId` |
| `PAYMENT_NOT_FOUND` | 404 | Unknown payment id |
| `INVALID_STATE_TRANSITION` | 409 | e.g. capture twice, refund before capture |
| `INVALID_CARD_TOKEN` | 422 | Token isn't `tok_visa_ok` or `tok_declined` |
| `REFUND_EXCEEDS_CAPTURED` | 422 | Refund total would exceed the captured amount |
| `INTERNAL_ERROR` | 500 | Unexpected error (details are logged, not returned) |

## Ledger

Every money movement is recorded as balanced double-entry postings, one account per merchant per currency:

| Event | Debit | Credit |
|---|---|---|
| Authorize | (nothing: no money moves) | |
| Capture | `PROCESSOR_RECEIVABLE` | `MERCHANT_PAYABLE` (merchant) |
| Refund | `MERCHANT_PAYABLE` (merchant) | `PROCESSOR_RECEIVABLE` |

- **Balanced:** `LedgerService` rejects any journal whose debits and credits differ in any currency.
- **Append-only:** Postgres triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on the ledger tables. Corrections are new reversing entries.
- **Audited:** `BalanceCheck` proves, with SQL over every entry, that debits equal credits per currency and that each merchant's balance equals captured minus refunded in the `payments` table.

## Build and test

```bash
./mvnw verify -DskipITs   # build + unit tests (no Docker needed)
./mvnw verify             # + integration tests (*IT, Testcontainers)
./mvnw spotless:apply     # format
./mvnw spotless:check     # check formatting (CI runs this)
```

- **Unit tests** are `*Test` (Surefire): `Money`, the status transition matrix, the `Payment` aggregate, and `LedgerService`.
- **Integration tests** are `*IT` (Failsafe, real Postgres via Testcontainers). They use one of two base classes:
  - `AbstractIT`: schema, ledger and balance-check tests. One shared container, and each test is rolled back.
  - `AbstractApiIT`: HTTP tests against the running app. These commit, so they get their own container, and `BalanceCheck` must pass after every test. They include the happy path, every error code, and concurrency tests (10 parallel refunds, 5 parallel captures).

CI (GitHub Actions) runs `spotless:check` and `./mvnw verify` on every push and pull request.

## Database migrations

Flyway migrations live in `src/main/resources/db/migration` and run on startup. Hibernate only validates the schema (`ddl-auto: validate`); it never changes it.

| Migration | Creates |
|---|---|
| `V1__init.sql` | Nothing (baseline) |
| `V2__create_merchants_and_payments.sql` | `merchants`, `payments`, with CHECKs for the amount rules and status/amount consistency |
| `V3__create_ledger.sql` | `ledger_accounts`, `journal_transactions`, `ledger_entries`, append-only triggers |
| `V4__create_refunds.sql` | `refunds` |

## Docs

- [CLAUDE.md](CLAUDE.md): project rules and layout
- [docs/DECISIONS.md](docs/DECISIONS.md): design decisions (context, choice, alternatives, trade-off)
- [docs/RESULTS.md](docs/RESULTS.md): measured results
