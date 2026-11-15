# ADR-0010: At-least-once activities with idempotency keys

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-05, NFR-02

## Context

Activities change the outside world: they charge cards, reserve stock and send emails. A process can die at
any moment, including after an activity has done its work and before its result is saved. When that happens,
the engine cannot know whether the work took place. It has two choices: run the activity again, or never run
it again.

No engine can guarantee that an activity's effect happens exactly once on its own. The gap between "the work
happened" and "the result is recorded" always exists, because they happen in two different systems.

## Decision

Activities run **at least once**, and every activity call gets a stable **idempotency key** so that the
system it calls can recognise a repeat:

- The key is `<workflow ID>/activity-<sequence number>`, for example `order-42/activity-2`. It is derived from
  the history, so every run of the same activity call gets the same key, on any machine.
- Activity code reads it with `ActivityContext.current().idempotencyKey()` and passes it to the external
  system, which stores it and ignores a second request with the same key.
- If a worker dies before saving the result, the task's lease expires and another worker runs the activity
  again with the same key. Once a result is saved, the activity is never run again.

## Alternatives considered

- **At-most-once: never retry an activity whose outcome is unknown.** No duplicates, but work is silently lost
  whenever a process dies at the wrong moment, which breaks the durability promise.
- **Two-phase commit with external systems.** In theory exactly-once, but almost no payment, email or HTTP API
  supports it, and it couples the engine's availability to theirs.
- **Exactly-once claimed by the engine alone.** Impossible in principle for external side effects; claiming it
  would mislead users.

## Consequences

- **Positive:** nothing is lost. Combined with idempotent external calls, the effect happens once. The
  guarantee is proven by two tests: a crash injected right after an activity ran, and a real `kill -9` of a
  separate process in the middle of an activity. In both, the activity runs again with the same key, the
  customer is charged once, and the workflow completes.
- **Negative:** activity authors must make their activities idempotent, usually by passing the key along.
  Activities without side effects need nothing.
- **Follow-ups:** the *Writing workflows* guide explains the key with examples. Retries (M2) reuse the same key
  for every attempt.
