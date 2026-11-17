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
