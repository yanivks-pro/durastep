# ADR-0031: Wake engines after the commit, not inside it

- **Status:** Accepted
- **Date:** 2026-11-18
- **Requirements:** NFR-06, NFR-09
- **Amends:** [ADR-0017](0017-postgres-as-the-task-queue.md), which sent `NOTIFY` inside the transaction that
  creates work, and the follow-up in [ADR-0011](0011-one-transaction-per-step.md)

## Context

Engines learn about new work through Postgres's `LISTEN`/`NOTIFY`, and until now every transaction that created
work also sent the `NOTIFY`. The scaling benchmark showed that this capped Durastep: three engines were only 8%
faster than one, and 62% to 90% of the time that Postgres connections were busy, they were waiting for one lock,
`object on pg_database, AccessExclusiveLock`. Postgres takes that lock when a transaction that sent a `NOTIFY`
commits, to keep notifications in commit order, and holds it until the commit has been written to disk. Every
commit that created work therefore waited for the previous one's disk write.

## Decision

Engines are woken in a short transaction of their own, right after the work has committed.

- **Collected, then sent.** While a transaction runs, the store records which queues it put work into. After a
  successful commit it sends one `pg_notify` per queue in a single autocommit statement, on the same connection.
  A transaction that fails sends nothing.
- **Short lock.** The notifying transaction writes no data, so its commit has nothing to flush to disk and holds
  the lock for microseconds.
- **Same wake-ups as before.** Every new piece of work still notifies, so idle engines are woken as quickly as
  before. Notifications now arrive only once the work is visible, which was already true.
- **A lost notification costs at most the safety poll.** If the process stops between the commit and the
  notification, or the notification fails, the work is safe in the database and engines find it at their next
  poll, within a second. The failure is logged and does not fail the committed work.

## Alternatives considered

- **Notify only when no other work is waiting.** Tried first: when a queue already had ready work, a new task
  skipped the `NOTIFY`, on the reasoning that busy workers poll again by themselves. It removed the lock, but under
  a steady load tasks piled up unclaimed while workers slept until the safety poll: step latency went from 12 ms
  to over a second at the median. Measured and rejected.
- **Polling only, without `NOTIFY`.** No lock at all, but every hand-off would wait for the next poll, or the
  database would be polled much more often.
- **`synchronous_commit = off`.** Shorter commits, but a crash of Postgres could lose work that callers were told
  was saved.

## Consequences

Measured with `make bench` and `make bench-scaling` on the same laptop before and after:

| | Before | After |
|---|---|---|
| One engine (scaling drain) | 138 workflows/s | 159 workflows/s |
| Three engines | 149 workflows/s (1.08×) | 228 workflows/s (1.43×) |
| Busy time waiting for the notify lock | 62% to 90% | none |
| Step latency, median / p99, under load | 12.1 / 20.6 ms | 15.9 / 26.6 ms |

- **Positive:** engines scale again, and the chaos test still finishes 1,000 workflows through 100 kills with
  none lost and none repeated. The largest wait is now Postgres writing its log, the cost of durable commits.
- **Negative:** one more round trip after every transaction that creates work, which adds a few milliseconds to
  each hand-off. With the lock gone, the size of the backlog now matters: the claim query slows as the `tasks`
  table collects dead rows, from 198 workflows/s with 2,000 queued to 138 with 20,000. That is the next thing to
  improve.
