# ADR-0009: Pure workflow runtime

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-07, FR-08, FR-26

## Context

Replay ([ADR-0008](0008-event-history-and-full-replay.md)) is the most subtle part of the engine. It must
behave identically on every run, and it must be tested against many histories: partial, complete, failed,
corrupted, and from older code. Tests that need a database for each case are slow and hard to make cover
every combination.

## Decision

The workflow runtime is a pure function with no database, network or clock:

```
decide(workflow definition, workflow ID, history) -> commands
```

- The worker loads the history, calls `decide`, and saves the resulting commands in one transaction. The
  runtime never touches the database; an ArchUnit rule forbids it to depend on `java.sql` or the store.
- Workflow code runs on a virtual thread. When it reaches a step without a result, the activity stub throws
  an internal `Error` that unwinds the workflow; the runtime detects code that swallows it.
- Time and randomness come from the history, not the machine: `ctx.now()` is the database time of the history
  event the code has reached, and `ctx.random()` is seeded from the workflow ID. Replays need no extra events.

## Alternatives considered

- **Runtime that reads and writes the database directly.** Fewer moving parts, but every replay test would
  need Postgres, and database errors would mix with replay logic.
- **Parking the workflow thread instead of unwinding it.** Simpler control flow, but parked threads pile up in
  memory with every decision.
- **Recording each `now()` and `random()` call as a history event.** Easy to explain, but it adds writes and
  lengthens histories for values that can be derived.

## Consequences

- **Positive:** replay is tested in milliseconds without Docker, including corrupted histories. The same
  function powers the replay test kit (`WorkflowReplayer`, M6) and property-based tests.
- **Negative:** workflow code must not catch `Throwable` or `Error`. `ctx.now()` advances only when the
  workflow reaches a new step, not continuously.
- **Follow-ups:** in M4, parallel steps (`ctx.async`) extend the runtime to several logical threads.
