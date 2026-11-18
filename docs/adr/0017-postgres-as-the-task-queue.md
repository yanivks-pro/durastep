# ADR-0017: Postgres is the task queue

- **Status:** Accepted; amended by [ADR-0031](0031-notify-after-commit.md), which sends the notification after
  the commit
- **Date:** 2026-11-15
- **Requirements:** FR-19, FR-24, C-01, C-02

## Context

Engines need to find new work quickly: a decision after an activity finishes, an activity after a decision
schedules it. Durastep has no server ([ADR-0002](0002-embedded-library.md)) and no other infrastructure than
Postgres ([ADR-0003](0003-postgres-only.md)). Polling the tasks table often finds work fast but costs queries
when nothing is happening; polling rarely is cheap but slow.

## Decision

The `tasks` table is the queue, and Postgres notifications wake idle engines.

- Workers claim tasks with `FOR UPDATE SKIP LOCKED`, in batches no larger than their free capacity
  ([ADR-0019](0019-one-decider-per-workflow.md)).
- Every transaction that creates work also calls `pg_notify` on a channel named after the schema, with the kind
  of work and its queue as the payload. Postgres delivers a notification only when the transaction commits, so
  an engine is never woken for work that was rolled back.
- Each engine keeps one connection that `LISTEN`s on the channel and wakes the matching poller at once. If that
  connection breaks, the engine logs one warning, reconnects with backoff from 1 to 30 seconds, and logs once
  when it is listening again.
- Pollers still look for work every second when nothing wakes them. That catches work whose notification was
  missed, and work that becomes ready later, such as a retry after its backoff.

## Alternatives considered

- **Fast polling only.** Simple and what v0.1 and v0.2 did every 100 ms, but most of those queries find
  nothing, and latency is still up to the polling interval.
- **A message broker for wake-ups.** Faster fan-out at very large scale, but a second system to run, and its
  messages would not be part of the same transaction as the work.
- **Long-held `FOR UPDATE` waits.** Workers could block on locked rows, but that holds a connection and a
  transaction per idle worker.

## Consequences

- **Positive:** an idle engine reacts in milliseconds. With notifications turned off on purpose, a five-step
  workflow took 11 seconds with one-second polling; with them it finished in under two.
- **Negative:** each engine holds one extra connection. Notifications do not pass through PgBouncer in
  transaction mode; there, engines fall back to one-second polling. The Postgres driver is now a compile-time
  dependency, because listening uses its `PGConnection` API.
- **Follow-ups:** named queues (FR-22) put the queue name in the payload so only matching engines react.
