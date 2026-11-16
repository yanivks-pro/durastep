# ADR-0018: Leases, fencing tokens and heartbeats

- **Status:** Accepted
- **Date:** 2026-11-15
- **Requirements:** FR-20, FR-21, AC-04

## Context

A worker that claims a task can stop without warning: its process crashes, its machine loses the network, or
it pauses for a long garbage collection or a frozen virtual machine. Others must eventually take its work
over, or the workflow is stuck. But a paused worker can also wake up later and try to save a result for work
that someone else has redone in the meantime: a "zombie". Its result must not be applied.

## Decision

Every claim is a **lease** with a **fencing token**, and long activities keep their lease alive with
**heartbeats**.

- A claim sets `lease_until` from the database clock and increments the task's `fencing_token`. Once the lease
  has expired, any worker may claim the task again, which increments the token again.
- Every write that completes a task starts with a fencing check: the task row is locked and its token and
  owner must still be the ones the worker claimed with. A zombie's write is rejected and rolled back.
- An activity's lease covers its attempt timeout plus one lease ([ADR-0015](0015-retries-as-delayed-tasks.md)),
  unless it sets `heartbeatTimeout`. Then the lease covers only that timeout, and
  `ActivityContext.heartbeat()` extends it, at most once in every third of the timeout.
- A heartbeat is fenced too. If the attempt has been taken over, `heartbeat()` throws
  `ActivityLeaseLostException`, so a zombie stops working instead of finishing for nothing.

## Alternatives considered

- **Locks held for the duration of the work.** No zombies, but a transaction and a connection stay open for as
  long as the activity runs, and a frozen worker holds its lock until the database notices the dead session.
- **Leases without fencing.** Takeover works, but a zombie that wakes up overwrites the newer result.
- **Fixed long leases without heartbeats.** Simple, but a crash is noticed only after the longest possible
  activity time.

## Consequences

- **Positive:** a crashed long activity is taken over after its heartbeat timeout: in a test, after about one
  second instead of the one-minute attempt timeout. Zombies can neither write results nor keep working
  unnoticed.
- **Negative:** activities must heartbeat to benefit, and a heartbeat costs a small update every third of the
  timeout. Work done by a zombie before it notices is wasted, and external effects rely on idempotency keys
  ([ADR-0010](0010-at-least-once-with-idempotency-keys.md)).
- **Proof (AC-04):** a test freezes a real worker process with `kill -STOP` in the middle of an activity. A
  second process takes the task over and completes it. When the first is resumed with `kill -CONT`, its
  heartbeat throws `ActivityLeaseLostException` and its attempt to save a result is rejected by the fencing
  check. The card is charged once and the invariants hold.
