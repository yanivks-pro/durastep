# ADR-0030: One trace per workflow, through a small hook in the core

- **Status:** Accepted
- **Date:** 2026-11-18
- **Requirements:** NFR-13

## Context

A workflow's work is spread over time and machines: a request starts it, one engine charges a card, another
engine retries it after a crash, a timer fires two hours later, a person approves it the next day. Logs carry
the workflow ID, but a tracing system such as Jaeger or Tempo
shows that work only if every piece of it carries the trace of the request that started it. That context lives
in the starting thread for a few milliseconds; the work happens long after, elsewhere. The core must not depend
on OpenTelemetry ([ADR-0006](0006-plain-jdbc-minimal-dependencies.md)), and tracing must never be able to
break a workflow.

## Decision

The trace context is captured once, stored with the workflow, and handed to a small hook every time an engine
does something for it.

- **A hook in the core, with no tracing dependency.** `WorkflowTracing` has a method to capture the caller's
  context at start, one to start the trace of an activity attempt, and calls for a timer that fired, a signal
  that arrived and the end of the workflow. `ActivityTrace` wraps the activity's code, so the attempt's span is
  current in the activity's own thread and the calls it makes join the trace. Engines take it with
  `tracing(...)`, clients with `withTracing(...)`; the default does nothing and stores nothing.
- **Stored with the workflow.** The captured fields, such as the W3C `traceparent`, go into a new nullable
  `workflows.trace_context` column at start. The engine reads them with the activity or decision it loads
  anyway, so tracing adds no queries.
- **Reported after the fact, never twice.** Timers, signals and the end are reported after the decision that
  saw them has committed, with the times recorded in the history, so a decision that is retried or discarded
  reports nothing. A crash right after the commit can lose a span, never an event.
- **Isolated.** Every call into the hook is guarded: an exception is logged once and ignored, and an activity
  whose wrapper fails runs without it.
- **OpenTelemetry in its own module.** `durastep-opentelemetry` implements the hook with the application's
  `OpenTelemetry`: spans named `activity Payments.charge`, `timer sleep PT2H`, `signal approve` and `workflow
  OrderWorkflow`, with the workflow's ID and type, the attempt and its outcome. A failed attempt records the
  error's type, never its message. A workflow started where no span is current gets a short `start` span as
  the root of its trace. The Spring Boot starter wires it in when the module and an `OpenTelemetry` bean are
  present.

## Alternatives considered

- **A long-lived workflow span with the steps under it.** The most natural picture, but a span that stays open
  for days across processes would need its ID created before it starts and the span exported when it ends,
  which the OpenTelemetry SDK does not support cleanly. The steps hang under the request's span instead, beside
  a `workflow` span that covers the whole run.
- **Tracing through `EngineListener`.** Listeners see counts and durations, not which workflow they belong to,
  and cannot make a span current inside the activity's thread.
- **OpenTelemetry in the core.** Simpler wiring, but every user would carry it, and the core would follow its
  release cadence.

## Consequences

- **Positive:** a request that starts a workflow shows, in one trace, every attempt of every activity with its
  outcome, the calls those activities made, timers with their real duration, signals and the end, whichever
  engine did the work. Tests check this with the OpenTelemetry SDK, including an attempt that fails and is
  retried, the user's own span inside an activity, and a workflow started where no trace was active; a test
  with a hook that throws everywhere shows the workflow is unaffected.
- **Negative:** one nullable column and one migration. Workflows started by a client without tracing have no
  stored context, so their spans form one trace per step rather than one per workflow. Spans of timers and
  signals appear only once the workflow has seen them.
