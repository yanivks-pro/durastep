# ADR-0012: A single writer to history, through an inbox

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-06, FR-23, FR-30

## Context

A workflow's history is numbered 1, 2, 3 with no gaps, and replay depends on that order. Several things
produce events for the same workflow: its decider, its activities finishing, and later timers, signals and
cancellations. An early design let each of them append to history directly.

Walking through the flows during design review showed a race: an activity finishing while the decider is
committing would both try to write the next sequence number. One of them fails, or worse, events end up in
an order the decider never saw.

## Decision

Only the workflow's **decider** writes to `history_events`. Everything else writes to an **inbox**:

- An activity result is inserted into `inbox` and makes sure a decide task exists. If the decider is busy at
  that moment, its task is marked `needs_rerun` instead.
- The decider loads history plus inbox, replays, and in its commit moves exactly the inbox rows it read into
  history, in arrival order, keeping the time each one arrived.
- When its task is marked `needs_rerun`, the decider puts the task back in the queue instead of deleting it, so
  anything that arrived during the decision is handled next.
- The database enforces at most one decide task per workflow with a partial unique index.

## Alternatives considered

- **Every component appends to history.** Fewer tables, but needs a lock per workflow on every write, and the
  decider could still commit decisions based on a history that changed underneath it.
- **A sequence or counter per workflow.** Avoids duplicate numbers, but not events the decider never saw.
- **An external queue per workflow.** Gives ordering, but adds a second system and breaks the one-transaction
  rule ([ADR-0011](0011-one-transaction-per-step.md)).

## Consequences

- **Positive:** history is written by one party at a time and always matches what the decider replayed. The
  race was found and fixed in design, before any code was written.
- **Negative:** each event from outside takes one extra step (inbox, then history), and the inbox adds a table.
- **Follow-ups:** timers, signals and cancellations (M2, M4) use the same inbox.
