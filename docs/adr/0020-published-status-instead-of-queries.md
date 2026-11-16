# ADR-0020: Signals in, published status out

- **Status:** Accepted
- **Date:** 2026-11-16
- **Requirements:** FR-14, FR-15, FR-39

## Context

Long-running workflows talk to people and other systems while they run: an expense waits for a manager's
decision, and support staff want to see where it stands. Information has to flow in both directions without
breaking the rules that keep a workflow durable: one writer to history
([ADR-0012](0012-single-writer-to-history.md)), one decider at a time
([ADR-0019](0019-one-decider-per-workflow.md)), and deterministic replay
([ADR-0008](0008-event-history-and-full-replay.md)).

## Decision

**In: signals.** `client.signal(id, name, payload)` writes the signal to the inbox in one transaction and wakes
the decider, which copies it into history. `ctx.awaitSignal(name, type[, timeout])` takes the oldest signal
with that name that no earlier call took, so signals that arrive before the workflow waits are kept, and each
is received exactly once, by the same call on every replay. A caller-supplied request ID makes retried sends
safe. A timeout is a timer step; whichever of the signal and the timer comes first in history decides the
result, and a later timer fire is ignored.

**Out: a published status.** `ctx.setStatus(text)` records a short status. It is not part of the history: each
decision saves the last value the code set, and `handle.describe()` returns it with the workflow's state and
blocked flag.

## Alternatives considered

- **On-demand queries** that run workflow code to answer a question. Flexible, but a query would have to
  replay the workflow outside a decision, alongside the one decider, or wait for it; it needs a running
  engine with the workflow's code. A status the workflow publishes itself is always consistent and readable
  with plain SQL, by the CLI and the dashboard.
- **Status as history events.** Gives a full timeline of statuses, but every status change would lengthen the
  history that each replay reads.
- **Signals delivered directly to a waiting decider.** Lower latency, but bypasses the inbox and loses signals
  that arrive while nothing waits for them.

## Consequences

- **Positive:** people and systems can steer workflows safely, and the current status is one indexed column
  away. Signals survive crashes and are never received twice.
- **Negative:** the status shows only the latest value, and only what the workflow chose to publish. A signal
  wait with a timeout leaves a timer behind if the signal wins; it fires later and is ignored.
- **Follow-ups:** the CLI shows status and history (M5); cancellation uses the same inbox
  (ADR-0021).
