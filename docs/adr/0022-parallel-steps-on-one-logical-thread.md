# ADR-0022: Parallel steps on one logical thread

- **Status:** Accepted
- **Date:** 2026-11-16
- **Requirements:** FR-17

## Context

Booking a flight and a hotel one after the other takes twice as long as needed. Workflow code should be able to
run steps at the same time. But replay ([ADR-0008](0008-event-history-and-full-replay.md)) must reach the same
decisions whatever order results arrive in, and whether it replays part of the history or all of it. Until now,
replay matched steps by global position: the n-th step reached had to be the n-th step in the history. With
steps running side by side, the order in which they are reached depends on which results came first, so
global positions no longer work.

## Decision

Branches of workflow code take turns on one logical thread, and receive results in history order.

- `ctx.async(...)` starts a **branch** and returns a `Promise`. Branch IDs follow the code: the main code is `0`,
  its branches `0.1`, `0.2`, and a branch inside `0.1` is `0.1.1`.
- **Each branch numbers its own steps.** A step is identified by its branch and its position in that branch,
  and is stored with its branch. Steps of the main code are stored without one, so older histories read the
  same.
- **One logical thread.** Each branch has its own virtual thread, but only one runs at a time. A branch runs until
  it has to wait: for an activity, a timer, a signal or another branch.
- **Results in history order.** A waiting branch lists what can answer it: a result, a signal, a cancellation,
  each with its history number. When no branch can run, the next answer by history number is delivered. So
  replay reaches every point in the same state as the decision that first got there.
- `Promise.allOf` waits for all branches and rethrows the failure that came first in the history.
  `Promise.anyOf` returns the branch that finished first in the history.
- Each branch has its own clock and random numbers. Only the main code may wait for signals. A cancellation
  reaches every branch at its next wait point, but branches started during cleanup are not cancelled again
  ([ADR-0021](0021-cooperative-cancellation.md)). When the main code finishes, the workflow ends and branches
  that are still waiting are dropped. Branches that only wait for each other block the workflow with a message.

## Alternatives considered

- **Real threads running branches at the same time.** Faster for heavy workflow code, but the order of their
  steps and of their effects on shared variables would depend on timing, which breaks replay.
- **Delivering results in a fixed turn order.** Simpler, but a replay of all of the history could take a
  different path than the decision that saw only part of it. A test showed this in 142 of 300 random runs.
- **A global step position, as before.** Works only if branches always reach their steps in the same order,
  which depends on which results arrive first.

## Consequences

- **Positive:** activities of different branches run at the same time: two one-second bookings finish in about
  one second. Replay stays deterministic: a test plays 300 random completion orders and replays every decision
  from part of the history, with identical results.
- **Negative:** branches share one logical thread, so heavy computation in workflow code is not spread across
  cores; workflow code should be light anyway. Waiting for signals in branches is not supported yet.
- **Proof:** with results delivered in turn order on purpose, the property test fails in 142 of 300 runs; with
  `anyOf` choosing by argument order, in 147.
