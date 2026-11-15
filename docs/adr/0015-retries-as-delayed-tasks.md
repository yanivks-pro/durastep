# ADR-0015: Retries are delayed tasks

- **Status:** Accepted
- **Date:** 2026-11-15
- **Requirements:** FR-10, FR-11, FR-13, FR-20

## Context

External calls fail for passing reasons: a timeout, a restart, a rate limit. Activities need retries with a
growing wait, a limit per attempt, and an optional limit for all attempts together. Retries must survive a
crash between attempts, follow the same policy on every machine, and must not add a second scheduling system
next to the task queue.

## Decision

A retry is the same activity task, made visible again later.

- When an attempt fails with a retryable error and attempts remain, one transaction increments `attempt`, sets
  `visible_at = now() + backoff`, records the error in `last_error` and clears the lease. The claim query
  already skips tasks that are not yet visible, so nothing else is needed.
- The wait doubles from `initialBackoff` up to `maxBackoff`, and each actual wait is drawn between 50% and
  100% of that value, so failures that happen together do not retry together.
- Only the final outcome reaches history. Workflow code sees a final failure as `ActivityFailedException`, which
  it may catch.
- The policy and timeouts are stored in the `ACTIVITY_SCHEDULED` event, so every worker, including one that
  takes over after a crash, applies the policy the workflow asked for. Histories written before the options
  existed read as the defaults.
- An attempt runs on its own virtual thread and is interrupted when it exceeds its limit; the timeout counts as
  a retryable failure. The total limit is checked before each retry, against the time the activity was
  scheduled.
- When an attempt starts, its lease is extended to the attempt limit plus one normal lease.

## Alternatives considered

- **A separate retry table or scheduler.** Clearer to describe, but a second place to poll, lock and keep
  consistent with the tasks.
- **Retrying inside the worker, in memory.** Simple, but a crash loses the retry and its wait.
- **Writing every failed attempt to history.** Complete, but histories grow with noise that replay must skip.

## Consequences

- **Positive:** retries survive crashes, cost no extra table, and every attempt uses the same idempotency key.
- **Negative:** a crashed activity is taken over only after its attempt limit plus a lease, not after a lease
  alone. Long activities that need faster takeover must wait for heartbeats (M3).
- **Why the lease extension matters:** without it, a test with two engines, a one-second lease and a
  2.5-second activity never finished: the engines kept taking the task from each other, and fencing rejected
  every result. With it, the activity runs once.
