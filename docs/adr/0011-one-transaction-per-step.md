# ADR-0011: One transaction per step

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-21, FR-23, FR-33, NFR-02

## Context

Each engine step changes several tables at once. Completing an activity, for example, writes its result to
the inbox, deletes the activity task and wakes the workflow's decider. If a crash lands between those writes,
the result could be lost, or the workflow could be left with nobody to move it forward.

At the same time, activities can run for minutes. Holding a database transaction open while they run would
pin connections and row locks for that long.

## Decision

Every engine step commits all of its changes in **one Postgres transaction**, and no transaction stays open
while user code runs.

- **Claim** is one short statement: it picks the next ready task with `FOR UPDATE SKIP LOCKED`, sets a lease
  and increments the task's fencing token.
- **Work** happens outside any transaction: replaying the workflow or running the activity.
- **Commit** is one transaction that starts with a **fencing check**: it locks the task row and confirms the
  token and owner are still ours. A stale worker gets `StaleTaskException`, and nothing is written.
- Commits use `READ COMMITTED`; the locked task row is what orders competing writers. Loading a workflow's
  history and inbox uses one `REPEATABLE READ` snapshot, so both are read as of the same moment.

## Alternatives considered

- **One transaction around claim, work and commit.** Simplest to reason about, but long activities would hold
  locks and connections, and a slow external call would block the database.
- **Several small transactions per step.** Less locking, but every gap between them is a place where a crash
  leaves half a step behind, which would need repair jobs.
- **`SERIALIZABLE` isolation everywhere.** Strong guarantees, but retries on serialisation failures would add
  complexity without protecting anything the task-row lock does not already protect.

## Consequences

- **Positive:** after a crash, every step has either fully happened or not happened at all. Late results from a
  frozen worker are rejected instead of applied twice.
- **Negative:** because claim and commit are separate, a step can be worked on twice (for example, an activity
  that runs, then its worker dies before committing). This is the at-least-once behaviour that idempotency
  keys address ([ADR-0010](0010-at-least-once-with-idempotency-keys.md)).
- **Follow-ups:** `NOTIFY` is issued inside the same transaction in M3, so it is delivered only on commit.
