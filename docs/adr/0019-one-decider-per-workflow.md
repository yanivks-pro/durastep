# ADR-0019: One decider per workflow

- **Status:** Accepted
- **Date:** 2026-11-15
- **Requirements:** FR-18, FR-23, FR-24

## Context

Many engines run at once, and each runs many decisions and activities in parallel. Different workflows should
make progress side by side, but a single workflow must never be decided twice at the same time: two deciders
replaying the same history would both try to write the next events, and one of them would base its decision
on a history that the other is changing.

## Decision

A workflow has at most one decision task, and that task is processed by one worker at a time.

- The database allows at most one `WORKFLOW` task per workflow, through a partial unique index. Anything that
  needs a new decision while one exists, such as an activity result, a fired timer or a signal, marks the
  existing task with `needs_rerun` instead of creating another.
- A worker claims a task with `FOR UPDATE SKIP LOCKED` and a lease, so other workers skip it until the lease
  expires. The fencing check at commit rejects a decider that lost its lease.
- Each engine has a fixed number of decision slots and activity slots (`workflowConcurrency`, default 10;
  `activityConcurrency`, default 100). A poller claims at most as many tasks as it has free slots, in one
  batch, and runs each on its own virtual thread. A full engine leaves work for others.

## Alternatives considered

- **An advisory lock per workflow during the decision.** Works, but holds a database session for the whole
  decision and needs separate handling of crashed lock holders.
- **Routing each workflow to a fixed engine by hashing its ID.** No contention, but rebalancing on scale-out
  and on failures becomes a distributed systems problem of its own.
- **Claiming one task at a time.** Simpler, but every task costs a round trip, and an engine cannot fill its
  free slots quickly.

## Consequences

- **Positive:** workflows run in parallel across engines while each workflow stays strictly sequential. A test
  with 10 engines and 200 workflows ran 1,000 activities exactly once and never had two decisions for one
  workflow running together.
- **Negative:** a workflow's decisions cannot be spread across engines, so one very busy workflow is limited
  to the speed of one decider.
- **Why the leases matter:** with the lease check removed from the claim query on purpose, engines took tasks
  from each other continuously; fencing rejected 5,640 stale results and the system made almost no progress.
