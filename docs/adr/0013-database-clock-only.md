# ADR-0013: The database clock is the only clock

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-12, FR-20, FR-26

## Context

Durastep runs on many application instances, and their clocks drift apart. Leases decide which worker owns a
task, and timers decide when a workflow wakes up. If each instance used its own clock, a worker whose clock
runs fast could treat another worker's lease as expired and take its task while it is still running.

Workflow code also needs a notion of time, and it must see the same time on every replay.

## Decision

Every time Durastep stores or compares comes from Postgres `now()`:

- Leases are computed in SQL (`lease_until = now() + lease`) and compared in SQL, never in Java.
- Every timestamp column defaults to `now()`. `now()` is the start time of the current transaction, so all
  writes of one step share one time.
- `ctx.now()` in workflow code returns the database time of the history event the workflow has reached. When
  an inbox entry is copied into history, it keeps the time it arrived, so the time the workflow saw is the time
  that is stored.

## Alternatives considered

- **Application clocks, kept in sync with NTP.** Usually close, but "usually" is not enough for ownership
  decisions; a paused VM or a clock step can break a lease.
- **Logical clocks only.** Avoid drift entirely, but users need real times for timers and business logic.

## Consequences

- **Positive:** all instances agree on time because they ask the same source. Replays see the same times.
- **Negative:** `ctx.now()` advances only when the workflow reaches a new step. Times are as accurate as the
  database server's clock, which operators should keep in sync.
- **Follow-ups:** durable timers (M2) fire by comparing `fire_at` with `now()` in SQL.
