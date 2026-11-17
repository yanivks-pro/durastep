# Architecture Decision Records

Each significant design decision in Durastep is recorded here. See [ADR-0001](0001-record-architecture-decisions.md)
for the rules, and [the template](0000-template.md) for the format.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions as ADRs | Accepted |
| [0002](0002-embedded-library.md) | Embedded library, no engine server | Accepted |
| [0003](0003-postgres-only.md) | Postgres only | Accepted |
| [0004](0004-java-25-virtual-threads.md) | Java 25 and virtual threads | Accepted |
| [0005](0005-maven-multi-module.md) | Maven multi-module build | Accepted |
| [0006](0006-plain-jdbc-minimal-dependencies.md) | Plain JDBC and minimal dependencies | Accepted |
| [0007](0007-schema-migrations-with-flyway.md) | Schema migrations with Flyway | Accepted |
| [0008](0008-event-history-and-full-replay.md) | Event history and full replay | Accepted |
| [0009](0009-pure-workflow-runtime.md) | Pure workflow runtime | Accepted |
| [0010](0010-at-least-once-with-idempotency-keys.md) | At-least-once activities with idempotency keys | Accepted |
| [0011](0011-one-transaction-per-step.md) | One transaction per step | Accepted |
| [0012](0012-single-writer-to-history.md) | A single writer to history, through an inbox | Accepted |
| [0013](0013-database-clock-only.md) | The database clock is the only clock | Accepted |
| [0014](0014-jsonb-payloads-and-event-codec.md) | JSONB payloads and an explicit event codec | Accepted |
| [0015](0015-retries-as-delayed-tasks.md) | Retries are delayed tasks | Accepted |
| [0016](0016-blocked-versus-failed.md) | BLOCKED versus FAILED | Accepted |
| [0017](0017-postgres-as-the-task-queue.md) | Postgres is the task queue | Accepted |
| [0018](0018-leases-fencing-and-heartbeats.md) | Leases, fencing tokens and heartbeats | Accepted |
| [0019](0019-one-decider-per-workflow.md) | One decider per workflow | Accepted |
| [0020](0020-published-status-instead-of-queries.md) | Signals in, published status out | Accepted |
| [0021](0021-cooperative-cancellation.md) | Cooperative cancellation | Accepted |
| [0022](0022-parallel-steps-on-one-logical-thread.md) | Parallel steps on one logical thread | Accepted |
| [0023](0023-operator-commands-work-directly-on-postgres.md) | Operator commands work directly on Postgres | Accepted |
| [0024](0024-retention-and-size-limits.md) | Retention and size limits | Accepted |
