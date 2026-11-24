# Decisions

## 2026-11-17: Spring Boot 4.0.x

- **Context:** New project; Boot 4.0 is the current stable line.
- **Choice:** Spring Boot 4.0.9 with the modular starters (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`), springdoc-openapi 3.0.x, and Testcontainers 2.x (managed by the Boot BOM).
- **Alternatives:** Boot 3.5.x.
- **Trade-off:** Newer, so fewer examples online and some renamed modules, but no migration later.

## 2026-11-17: Failsafe vs Surefire

- These are the official build plugins for Apache Maven used to execute automated tests in Java projects.
- **Surefire:** Runs fast, isolated unit tests during the test phase of the Maven build lifecycle
- **Failsafe:** Runs broader integration tests (which may rely on external resources like databases, web servers, or Docker containers) during the integration-test and verify phases.

## 2026-11-17: Integration tests run under Failsafe

- **Context:** Integration tests need Docker; unit tests should run without it.
- **Choice:** `*Test` runs under Surefire and `*IT` under Failsafe during `verify`. `-DskipITs` skips the ITs.
- **Alternatives:** JUnit tags with Surefire groups; a separate Maven profile.
- **Trade-off:** Relies on a naming convention, but it is Maven's standard one and needs no extra config.

## 2026-11-17: One shared Postgres container for all ITs

- **Context:** Starting a container per test class is slow and breaks Spring's context cache.
- **Choice:** `AbstractIT` holds a static `PostgreSQLContainer` started in a static block and wired with `@ServiceConnection`. Ryuk removes it when the JVM exits.
- **Alternatives:** `@Testcontainers` + `@Container` per class; Testcontainers JDBC URL.
- **Trade-off:** Tests share one database, so each test must create its own data and not depend on a clean DB.

## 2026-11-17: Local Postgres on host port 5433

- **Context:** The development machine already runs a native Postgres on 5432, so the app silently connected to it instead of the container.
- **Choice:** docker-compose publishes Postgres on 5433, and the default `DB_URL` points there.
- **Alternatives:** Keep 5432 and require stopping any local Postgres; make the port an env var in compose.
- **Trade-off:** Non-standard port to remember; `DB_URL` still overrides it.

## 2026-11-17: Hibernate validates, Flyway owns the schema

- **Context:** Schema changes must be reviewable and repeatable, especially for append-only ledger tables.
- **Choice:** `spring.jpa.hibernate.ddl-auto=validate`; all DDL goes through Flyway migrations.
- **Alternatives:** `update` / `create-drop`.
- **Trade-off:** Every entity change needs a migration.

## 2026-11-17: Money value type

- **Context:** Amounts must be exact, currency-safe and non-negative unless a caller deliberately needs a signed value (e.g. ledger debits/credits).
- **Choice:** `record Money(long minorUnits, java.util.Currency currency)`. `Money.of` rejects negatives; `subtract` refuses a negative result; `subtractAllowingNegative` is the only way to get one. Mismatched currencies throw `CurrencyMismatchException` (an `IllegalArgumentException`). Overflow throws via `Math.addExact`/`subtractExact`. `toDisplayString()` takes decimal places from the currency and ignores locale.
- **Alternatives:** A final class with a private constructor (could block `new Money(-5, CAD)` entirely); a currency code `String` or custom enum instead of `java.util.Currency`.
- **Trade-off:** A record's canonical constructor must be public, so negatives can still be built with `new Money(...)`. The convention is to always use `Money.of`. In exchange we get value equality, `hashCode` and accessors for free. `java.util.Currency` gives ISO-4217 validation and decimal places without a lookup table.

## 2026-11-17: Payment status transitions live in the enum

- **Context:** Payment state changes must follow one agreed state machine, and every service that moves a payment must use the same rules.
- **Choice:** `PaymentStatus` holds a static transition map as the single source of truth; services call `requireTransitionTo`, which throws `IllegalPaymentTransitionException`. Allowed: PENDING→AUTHORIZED/FAILED, AUTHORIZED→CAPTURED/FAILED, CAPTURED→PARTIALLY_REFUNDED/REFUNDED, PARTIALLY_REFUNDED→PARTIALLY_REFUNDED/REFUNDED. REFUNDED and FAILED are terminal. No auto-capture (PENDING→CAPTURED).
- **Alternatives:** A transition table in the database enforced by a trigger; Spring Statemachine.
- **Trade-off:** The database cannot reject an illegal transition on its own. It only enforces valid status values and the amount invariants (`amount > 0`, `0 <= refunded <= captured <= amount`) through named CHECK constraints, which `PaymentsSchemaIT` proves. Simple, testable Java in exchange for trusting the app layer on transitions.

## 2026-11-17: processor_ref is unique

- **Context:** One processor charge must never map to two payments, or a processor callback could update the wrong one.
- **Choice:** `UNIQUE (processor_ref)`; NULL allowed (and repeatable) until the processor responds.
- **Alternatives:** No constraint; uniqueness per merchant.
- **Trade-off:** If a processor ever reuses refs across merchants we'd need to scope it per merchant.

## 2026-11-17: why PARTIALLY_REFUNDED is its own state

- **Easy to query:** WHERE status = 'PARTIALLY_REFUNDED' is simpler to read and index than an amount comparison, which helps with dashboards and support tools.
- **Clearer for API clients and webhooks:** Merchants see an explicit state instead of having to compare refunded with captured themselves.
- **Rules can depend on:** If partially refunded payments ever need different handling (disputes, reporting, settlement), you can branch on the status.

## 2026-11-17: Payment status must match its amounts

- **Context:** `status` and `captured_minor`/`refunded_minor` both describe how far a payment has progressed. Without a link between them they can drift (e.g. `CAPTURED` with a refund recorded).
- **Choice:** `payments_status_matches_amounts` CHECK in V2: PENDING/AUTHORIZED/FAILED have nothing captured or refunded; CAPTURED has `captured > 0` and no refund; PARTIALLY_REFUNDED has `0 < refunded < captured`; REFUNDED has `refunded = captured > 0`. Written as `CASE status ... ELSE TRUE` so unknown statuses are left to `payments_status_valid`. Postgres reports failing CHECKs in name order, and each constraint should have one job.
- **Alternatives:** Drop PARTIALLY_REFUNDED and derive it from the amounts; enforce consistency only in Java.
- **Trade-off:** Status and amounts must be written in the same UPDATE. In return the database rejects any row whose status disagrees with its money. Folded into V2 (not a V3) because V2 had not been applied to any shared database yet.

## 2026-11-17: Ledger chart of accounts and posting table

- **Context:** Every money movement must be recorded as balanced double entries, with a merchant's balance traceable to their payments.
- **Choice:** Two account types. `PROCESSOR_RECEIVABLE` (platform asset, one per currency: captured money the processor owes us) and `MERCHANT_PAYABLE` (liability, one per merchant and currency: money we owe the merchant).

  | Event | Debit | Credit | Amount |
  |---|---|---|---|
  | Authorize | (nothing) | (nothing) | |
  | Capture | `PROCESSOR_RECEIVABLE` | `MERCHANT_PAYABLE` (merchant) | captured amount |
  | Refund (partial or full) | `MERCHANT_PAYABLE` (merchant) | `PROCESSOR_RECEIVABLE` | refund amount |

  A merchant's balance (credits − debits on their payable) therefore always equals Σ captured − Σ refunded.
- **Alternatives:** Post authorizations as holds in memo accounts; a fuller chart (cash, fees, settlement) up front.
- **Trade-off:** An authorization moves no money, so posting it would need memo accounts and a reversing journal on every capture, void and expiry. The hold is already visible as `status = AUTHORIZED`. Fees and payouts are not modelled yet; when payouts arrive, BalanceCheck's expected balance becomes captured − refunded − paid out.

## 2026-11-17: Ledger is append-only, enforced by triggers

- **Context:** Ledger history must never change; corrections are new reversing entries.
- **Choice:** `ledger_reject_modification()` raises on `BEFORE UPDATE OR DELETE` (row) and `BEFORE TRUNCATE` (statement) on `ledger_entries` and `journal_transactions`. TRUNCATE needs its own trigger because it skips row triggers. Also: an entry's currency must match its account's (composite FK), and a payment can be captured into the ledger only once (partial unique index).
- **Alternatives:** Only `REVOKE UPDATE, DELETE` from the app's DB role; rely on the app never issuing them.
- **Trade-off:** A table owner can still `DISABLE TRIGGER`, so `BalanceCheck` audits the result independently. Revoking privileges from the app role is a good extra layer once we have separate migration and app roles.

## 2026-11-17: Ledger uses JdbcClient, not JPA

- **Context:** The ledger only inserts rows and runs aggregate queries.
- **Choice:** `LedgerRepository` and `BalanceCheck` use plain SQL through Spring's `JdbcClient`. `LedgerService.post` checks that debits equal credits per currency (summed with `Money`) before anything is written, in one transaction.
- **Alternatives:** JPA entities marked immutable.
- **Trade-off:** No dirty checking or entity lifecycle that could ever issue an UPDATE, and the SQL is visible. In exchange we map rows by hand.

## 2026-11-17: Integration tests roll back

- **Context:** All ITs share one database, and `BalanceCheck` reads every row, so data left behind by one test breaks another.
- **Choice:** `AbstractIT` is `@Transactional`; each test's writes are rolled back.
- **Alternatives:** Truncate tables between tests (impossible for ledger tables by design); a database per test class.
- **Trade-off:** Code that runs on another thread (e.g. a request served by the embedded server) is outside the test transaction. We'll handle that when the first HTTP-level IT arrives.

## 2026-11-17: API amounts are integer minor units

- **Context:** Clients and the domain must agree on amounts with no rounding.
- **Choice:** Requests and responses carry `amount` as an integer in minor units plus a `currency` code (`1050` + `CAD` = 10.50 CAD), mapped straight to `Money`. Refunds carry only `amount`; the currency is the payment's.
- **Alternatives:** Decimal strings (`"10.50"`).
- **Trade-off:** Clients must know each currency's decimal places (JPY has 0), but nothing is ever parsed as floating point.

## 2026-11-17: A declined card is a 201 FAILED payment

- **Context:** `tok_declined` is a normal business outcome, not a bad request.
- **Choice:** `POST /v1/payments` stores the payment and returns 201 with `status: FAILED`. Only unknown tokens are an error (`422 INVALID_CARD_TOKEN`).
- **Alternatives:** `402 CARD_DECLINED` problem response.
- **Trade-off:** Clients must check `status`, but every attempt has a payment record to look up.

## 2026-11-17: Errors are RFC 7807 problem+json with a code

- **Context:** Clients need one error shape and a stable, machine-readable reason.
- **Choice:** `ApiExceptionHandler` returns Spring's `ProblemDetail` (`type`, `title`, `status`, `detail`, `instance`) plus `code`; validation errors add `errors[{field, message}]`. Codes: `VALIDATION_FAILED`, `MALFORMED_REQUEST` (400), `MERCHANT_NOT_FOUND`, `PAYMENT_NOT_FOUND` (404), `INVALID_STATE_TRANSITION` (409), `INVALID_CARD_TOKEN`, `REFUND_EXCEEDS_CAPTURED` (422), `INTERNAL_ERROR` (500, generic detail, full error logged). Spring's own MVC errors (unknown path, wrong method) use the HTTP status name as `code`.
- **Alternatives:** A custom error envelope.
- **Trade-off:** Follows a standard that tools understand; `type` is a relative URI (`/problems/...`) until we host docs for it.

## 2026-11-17: Capture and refund lock the payment row

- **Context:** Refund totals must never exceed captured, even under concurrent requests; a payment is captured once.
- **Choice:** Capture and refund load the payment with `SELECT … FOR UPDATE` (`PaymentRepository.findByIdForUpdate`), so concurrent requests run one at a time and each sees the latest totals. The payment, refund row and ledger journal share one transaction. The V2 CHECK constraints remain the backstop.
- **Alternatives:** Optimistic locking only (`@Version`): concurrent valid refunds would fail and need client retries.
- **Trade-off:** Requests for the same payment queue up. `ConcurrentPaymentIT` (10 parallel refunds, 5 parallel captures) passes with the lock and fails without it.

## 2026-11-17: Refunds have their own table; the processor is a seam

- **Context:** GET must list refunds, and a refund will later have its own lifecycle with a real processor.
- **Choice:** A `refunds` table (V4), separate from ledger journals, which record money movement only. `FakeCardProcessor` (only `tok_visa_ok` / `tok_declined`) sits behind one `authorize` method so a real client with fault injection can replace it.
- **Alternatives:** Derive refunds from ledger REFUND journals.
- **Trade-off:** Refund rows and `payments.refunded_minor` must agree; they are written in the same transaction. BalanceCheck could also compare them later.

## 2026-11-17: API tests use their own database

- **Context:** HTTP requests run on server threads, so API tests commit. Their data is balanced, but it would change the global totals other ITs assert exactly.
- **Choice:** `AbstractApiIT` has its own Postgres container (so its own Spring context) and runs `BalanceCheck` after every test.
- **Alternatives:** Relative assertions in BalanceCheckIT; cleaning up (impossible for append-only ledger tables).
- **Trade-off:** A second container and context, about 5 seconds per build.

## 2026-11-17: Minimal AWS deploy (week-one, expanded in Phase 4)

- **Context:** The app should run in the cloud from week one, at close to zero cost, without building production infrastructure yet.
- **Choice:** ca-central-1, default VPC. One ECS Fargate task (0.5 vCPU, 1 GB, x86) in a public subnet with a public IP and **no NAT gateway or load balancer**. RDS PostgreSQL 16 `db.t4g.micro`, single-AZ, `publicly_accessible = false`, reachable only from the task's security group. Image in ECR, logs in CloudWatch (7 days), a $25/month AWS Budget alert. Deploy order: create ECR, push the image (tagged with the git SHA), then apply the rest (`scripts/deploy.sh`).
- **Alternatives:** Private subnets + NAT gateway (~$35/month extra); ALB in front (~$18/month extra); App Runner or Elastic Beanstalk (less like the Phase 4 target).
- **Trade-off:** About **$40/month if left running** (estimate from on-demand list prices: Fargate ~$20, RDS ~$13, storage ~$2.50, public IPv4 ~$3.65, Secrets Manager $0.40, logs/ECR ~$1). Run `scripts/teardown.sh` when not in use. In exchange, it is very far from production-ready (see below).

## 2026-11-17: Terraform state is local for now

- **Context:** One person deploys from one laptop; remote state adds an S3 bucket and lock setup before there is anything to share.
- **Choice:** Local `terraform.tfstate`, git-ignored.
- **Alternatives:** S3 backend with state locking.
- **Trade-off:** No locking, no history, and the state file holds the DB password in plaintext. Losing it orphans the AWS resources (they would have to be deleted by hand). Move to S3 with locking and encryption before a second person or CI runs Terraform (Phase 4).

## 2026-11-17: DB password generated by Terraform, stored in Secrets Manager

- **Context:** The task needs the DB password without it being baked into the image or task definition.
- **Choice:** `random_password`, stored in a Secrets Manager secret; ECS injects it as `DB_PASSWORD` at task start. The execution role can read only that secret.
- **Alternatives:** RDS-managed master password (`manage_master_user_password`).
- **Trade-off:** The RDS-managed secret rotates every 7 days, and a running task keeps the password it started with, so new DB connections would fail after rotation until the task restarts. The Terraform-generated password doesn't rotate and sits in local state. Revisit with an app-specific DB user and rotation-aware connection handling in Phase 4.

## 2026-11-17: What the minimal AWS setup is NOT ready for

Known gaps, to fix in Phase 3 (auth) and Phase 4 (infrastructure):

1. **No authentication**: anyone with the IP can create merchants and payments (Phase 3: API keys).
2. **No HTTPS, no load balancer, no domain**: plain HTTP on port 8080, and the public IP changes on every deploy or restart.
3. **Task exposed directly to the internet**: port 8080 open to the world, no WAF or rate limiting, Swagger UI public.
4. **No redundancy**: one task, single-AZ database, no auto-scaling, no health-checked load balancer.
5. **Database credentials**: the app uses the RDS master user; the password is in plaintext in local Terraform state; no rotation.
6. **Weak data protection**: 1-day backups, no final snapshot, no deletion protection. Teardown deletes all data.
7. **Local Terraform state**: no locking, single copy on one machine.
8. **Default VPC**: the database's subnets route to the internet gateway (still unreachable: no public IP, and its security group admits only the app), but there's no private-subnet isolation.
9. **Supply chain**: base images not pinned by digest, mutable ECR tags, no image signing.
10. **Operations**: logs only (7 days), no metrics, alarms, tracing or runbook. Flyway migrates on app startup (risky with several tasks). Manual deploys from a laptop with personal credentials, no CD pipeline.

## 2026-11-18: Idempotency keys in the same transaction, with an advisory try-lock

- **Context:** A client that retries after a timeout must never charge, capture or refund twice. CLAUDE.md requires money changes to be safe under retries and concurrency.
- **Choice:** `POST /v1/payments`, `/capture` and `/refunds` require an `Idempotency-Key` header (1–255 chars), scoped per merchant. `IdempotencyService.execute` runs in **one transaction** with the business operation:
  1. `pg_try_advisory_xact_lock(hash(merchant, key))`. If it's held, return `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` immediately.
  2. Look up the key: expired keys are deleted and the request runs again; a matching fingerprint replays the stored status and body (`Idempotent-Replayed: true`); a different fingerprint returns `422 IDEMPOTENCY_KEY_REUSED`.
  3. Insert the key `IN_PROGRESS`, run the operation, store the response as `COMPLETED`, commit.

  The fingerprint is SHA-256 of method + path + the request body as key-sorted JSON, so the same body sent to another payment is "different", and whitespace or field order don't matter. Keys live 24 hours; an hourly job deletes expired ones. `POST /v1/merchants` has no key: it moves no money, and has no merchant to scope a key to until Phase 3.
- **Alternatives:** Two transactions (commit `IN_PROGRESS` first, then do the work), which leaves stuck keys after a crash and needs recovery; blocking on the primary key instead of a try-lock (duplicates would wait and replay rather than get 409); a servlet filter (can't share the business transaction).
- **Trade-off:** Because the key and the business write commit together, a committed key is always `COMPLETED`; `IN_PROGRESS` exists only inside the open transaction, which is why the advisory lock is needed to detect it. The primary key `(merchant_id, idempotency_key)` is the backstop: with the lock removed, duplicates still never act twice, but they fail with 500 instead of a clean 409 (`ConcurrentIdempotencyIT` shows this). A 64-bit hash collision between two different keys could give a rare false 409; the client simply retries. Re-using a key after 24 hours runs the request again, as documented.

## 2026-11-18: Only successful responses are stored for an Idempotency-Key

- **Context:** Should a retry replay an earlier error (e.g. `REFUND_EXCEEDS_CAPTURED`) or run again?
- **Choice:** Only 2xx results are stored. A declined card is a 201 `FAILED` payment, so it is stored and never re-authorized. When the operation throws, the transaction rolls back and the key row with it, so the client can fix the request and retry with the same key.
- **Alternatives:** Store 4xx business errors too (Stripe-like), using a savepoint to roll back the business writes while committing the key.
- **Trade-off:** Much simpler and falls out of the single transaction. A retried failure re-runs, which is safe because a failed request changed nothing.

## 2026-11-23: MCP server in-process, plain MCP Java SDK, stdio

- **Context:** An AI assistant (e.g. Claude Desktop) should be able to read payments, move money and check the ledger through MCP tools, with every existing rule (validation, idempotency, row locks, ledger balance) still applied.
- **Choice:** The same jar runs as an MCP server under the `mcp` profile: no web server, JSON-RPC over stdin/stdout, built with the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp` 2.1.0). Tools are Spring beans that call the existing services. Logs go to stderr because stdout is the protocol channel. When the client closes stdin, the app shuts down so no JVM is left holding database connections.
- **Alternatives:** Spring AI's MCP server starter (annotated tools, generated schemas), but every Spring AI 2.x release targets Spring Boot 4.1 or newer and this project is on 4.0.9. A separate server that calls the REST API, which would be more decoupled but needs its own runtime, duplicates error mapping, and can only be tested end-to-end. Streamable HTTP transport inside the running service, which Claude Desktop can't launch directly.
- **Trade-off:** Tool input schemas are written by hand instead of generated. The MCP process is a second JVM on the same database, which is safe because locking, idempotency and the CHECK constraints live in PostgreSQL. It also runs Flyway and the idempotency-key cleanup job on startup, as the web app does.

## 2026-11-23: REST and MCP share one idempotent write path

- **Context:** An agent might create a payment through MCP and retry it through REST (or the reverse). With two copies of the idempotency wiring, the request fingerprints could drift apart and the same key could charge twice.
- **Choice:** `PaymentCommands` holds the `IdempotencyService.execute` calls for create, capture and refund, with the fingerprint built from the REST method and path (`POST /v1/payments/{id}/refunds`). `PaymentController` and the MCP tools both call it.
- **Alternatives:** Duplicate the calls in the MCP tools; have the MCP tools call the REST API over HTTP.
- **Trade-off:** One more small class, and MCP fingerprints are named after REST paths. In exchange, a key means one operation however it arrives, and the existing idempotency tests cover both entry points.
