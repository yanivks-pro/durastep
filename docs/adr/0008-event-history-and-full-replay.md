# ADR-0008: Event history and full replay

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-05, FR-06, FR-07, FR-08

## Context

A workflow can be interrupted at any moment: a deployment, a crash, a lost database connection. When it
resumes, possibly on another machine, it must continue exactly where it stopped. Steps that already ran,
such as charging a card, must not run again, and their results must be available to the rest of the code.

Workflow code is plain Java, so the engine cannot save and restore a running method's local variables and
call stack.

## Decision

Each workflow has an append-only, numbered **history** of events stored in Postgres. To resume, the engine
**replays the workflow code from the start** against that history:

- When the code calls an activity that the history shows as completed, the call returns the saved result
  immediately and nothing is executed.
- When the code reaches a step that is not in the history yet, the engine records a command to schedule it
  and pauses the workflow.
- Each activity call is matched to the history by position and by name (`Interface.method`). If the code and
  the history disagree, the engine throws `NonDeterminismException` and saves nothing.

History is replayed in full on every resume. Caching to skip replay is a measured optimisation for later.

## Alternatives considered

- **Snapshots of workflow state.** Saving variables explicitly after each step avoids replay, but forces
  workflow authors to model their state by hand and to keep it compatible across code changes.
- **Serialising the call stack (continuations).** Resumes without replay, but needs bytecode instrumentation
  or a special runtime, and saved stacks break whenever the code changes.
- **A state machine or DSL instead of plain code.** Easy to resume, but gives up the main idea: workflows
  written as ordinary Java.

## Consequences

- **Positive:** workflows read like normal code. The history is a complete audit trail and makes every
  workflow reproducible in tests.
- **Negative:** workflow code must be deterministic: no direct clock, randomness or I/O. Replay cost grows with
  history length, so very long workflows need the history-length guard (FR-36).
- **Follow-ups:** a guide on determinism rules; versioning for changing running workflows (M6).
