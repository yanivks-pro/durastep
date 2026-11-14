# ADR-0003: Postgres only

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** C-02, NFR-20

## Context

Because Durastep has no server ([ADR-0002](0002-embedded-library.md)), the database does all the coordination
between application instances. Two features make this efficient:

- `SELECT … FOR UPDATE SKIP LOCKED` lets many workers claim different tasks from one table without blocking
  each other.
- `LISTEN/NOTIFY` wakes idle workers when new work arrives, so they do not have to poll often.

Correctness depends on the exact locking and transaction behaviour of the database, so it can only be proven
on the database that runs in production.

## Decision

Postgres is the only supported database, version 16 or newer. All tests that touch the database run against a
real Postgres in Docker through Testcontainers. CI tests both Postgres 16 and the latest release.

## Alternatives considered

- **Support several databases through standard SQL.** Wider reach, but `SKIP LOCKED` and notifications differ
  between databases or are missing, and each database would need its own concurrency proof.
- **H2 or another in-memory database for tests.** Faster tests and no Docker, but its locking does not match
  Postgres, so passing tests would not show the engine is correct.
- **Embedded Postgres binaries for tests.** No Docker needed, but it adds a dependency that must ship native
  binaries for every Postgres version and platform. Docker is already required for the demo, and
  Testcontainers runs the official Postgres images.

## Consequences

- **Positive:** one database, tested rigorously. The engine can use Postgres-specific features such as JSONB,
  partial indexes and advisory locks without abstraction layers.
- **Negative:** teams on MySQL, Oracle or SQL Server cannot use Durastep. Docker is required to run the test
  suite.
- **Follow-ups:** document what the engine needs from Postgres (for example, `NOTIFY` does not work through
  PgBouncer in transaction mode) in the Operations guide.
