# ADR-0006: Plain JDBC and minimal dependencies

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** C-05, C-06, Q-13

## Context

Durastep runs inside other people's applications ([ADR-0002](0002-embedded-library.md)). Every library it
brings can clash with a version the application already uses. The engine's correctness also depends on exact
SQL: row locks, `SKIP LOCKED`, one transaction per step and fencing checks. That SQL must be visible and
reviewable, not generated.

## Decision

`durastep-core` uses plain JDBC with hand-written SQL, and depends on at most five libraries:

| Library | Why |
|---|---|
| PostgreSQL JDBC driver | Database access, including `LISTEN/NOTIFY` |
| Jackson | JSON payloads |
| Flyway | Schema migrations |
| SLF4J API | Logging, without forcing a logging backend |
| Micrometer core | Metrics, without forcing a monitoring system |

Each one is added in the milestone that first uses it. The application supplies its own `DataSource` and
connection pool. The core never depends on Spring; an ArchUnit test enforces this.

## Alternatives considered

- **JPA or Hibernate.** Less SQL to write, but it hides the exact queries and locking that correctness depends
  on, and it is a large dependency.
- **jOOQ.** Type-safe SQL that stays close to the real queries, but it is a large dependency with code
  generation in the build, for a schema of only five tables.
- **Spring `JdbcTemplate`.** Convenient, but it would bring Spring into the core.
- **A bundled connection pool (HikariCP).** Simpler setup, but applications already have a pool, and two pools
  compete for database connections.
- **Lombok, Guava, Apache Commons, reactive libraries.** Records, the JDK and virtual threads already cover what
  the core needs.

## Consequences

- **Positive:** every query is visible and reviewed. Small dependency footprint, with few version clashes.
- **Negative:** more code to write by hand: row mapping and transaction handling.
- **Follow-ups:** Spring, picocli and Javalin belong only in their own optional modules.
