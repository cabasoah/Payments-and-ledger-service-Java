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
