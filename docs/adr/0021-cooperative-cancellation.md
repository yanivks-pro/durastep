# ADR-0021: Cooperative cancellation

- **Status:** Accepted
- **Date:** 2026-11-16
- **Requirements:** FR-16, FR-40

## Context

A customer withdraws an order while it is being processed. Stopping the workflow is not enough: money may
already have been taken and must be refunded, and an activity may be halfway through a call to another system.
Killing workflow code from outside would leave such work half-done, and killing activity threads cannot undo
what an external system has already done.

## Decision

Cancellation is a request that the workflow handles itself.

- `client.cancel(id)` sets `cancel_requested`, adds a `CANCEL_REQUESTED` entry to the inbox and wakes the
  decider, in one transaction. Repeating it, or cancelling a finished workflow, does nothing.
- Workflow code receives a `CancelledException` at its **first wait point** after the request: an activity call,
  a sleep or a signal wait. The point is decided from the history: a result recorded before the request is
  returned normally, and a result recorded after it is ignored. When cancellation replaces a step that was not
  yet scheduled, that step is not scheduled and does not take a step number, so cleanup steps replay exactly.
- After that, code runs normally, so it can clean up with ordinary activities, which are retried and
  crash-safe. The workflow then ends **CANCELLED** whether it rethrows the exception or returns, unless it throws
  `WorkflowFailedException`, which makes it FAILED. `result()` throws `WorkflowCancelledException`.
- Activities that are already running are not interrupted. When the workflow ends, their tasks are removed, and
  their results are rejected when they arrive.
- A workflow that finishes without reaching another wait point completes normally: the request came too late.

## Alternatives considered

- **Terminating the workflow immediately.** Simple and fast, but skips all cleanup. It remains useful for
  operators, as a separate `terminate` command (M5).
- **Interrupting running activities.** Stops local work sooner, but cannot undo external effects, and an
  interrupted call leaves its outcome unknown.
- **Delivering cancellation at any point in the code.** Possible only by interrupting the workflow's thread,
  which would make the moment of cancellation depend on timing instead of on the history.

## Consequences

- **Positive:** cancelled workflows clean up after themselves, durably. The point of cancellation is
  deterministic, so replay gives the same result every time. A thirty-day sleep is cancelled at once.
- **Negative:** an activity that is running when the request arrives still finishes and still has its external
  effect; the workflow must compensate for it. A workflow blocked by a bug cannot be cancelled until it is
  fixed or terminated.
- **Follow-ups:** sagas build compensations on top of this; `terminate` for operators comes with the CLI.
