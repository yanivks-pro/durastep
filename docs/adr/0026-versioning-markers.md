# ADR-0026: Versioning with markers in the history

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** FR-09, FR-08

## Context

Workflows can run for days or months, and code changes much more often. Replay
([ADR-0008](0008-event-history-and-full-replay.md)) runs the new code against the history of each running
workflow, and when the two disagree, the workflow is blocked with a mismatch instead of guessing
([ADR-0016](0016-blocked-versus-failed.md)). That keeps workflows correct, but it means that changing the steps of
a workflow type blocks every instance that has already gone past the change. Teams need a way to deploy a change
while those instances finish on the code they started with.

## Decision

Workflow code asks which path to take with `ctx.version(changeId, maxVersion)`, and the answer is kept in the
history.

- When a workflow reaches the check for the first time, the answer depends on whether it has already gone past
  that point in its code, which the history shows: if its current branch has recorded a step beyond the current
  position, it went past it under the old code and gets `DEFAULT_VERSION` (0). Otherwise it gets `maxVersion`,
  and a `VERSION_MARKER` event with the change ID and the version is added to the history.
- On replay, a recorded marker always wins, even when newer code passes a higher `maxVersion`, and the same change
  ID returns the same version for the rest of the run.
- A marker is not a step. Adding a version check does not change the step numbers of any history, so old
  workflows still match their history wherever the check is placed.
- A workflow waiting before the changed code, for example for a signal, has not gone past it and takes the new
  path. Only workflows that already ran the old code there keep it.
- The CLI shows markers in the timeline: "uses version 1 of send-sms-instead-of-email".

## Alternatives considered

- **Markers as steps.** Simpler to find on replay, but adding a check would shift the step numbers of every
  running workflow and cause the very mismatch it is meant to prevent.
- **A version per workflow type, chosen at start.** Every instance keeps the code it started with; but the
  engine would have to keep every old version of the class loaded, and a fix could never reach running
  workflows.
- **Deciding by deploy time or a global flag.** Depends on clocks and configuration rather than on what each
  workflow actually did, so replay could take a different path than the original run.

## Consequences

- **Positive:** a change can be deployed while workflows are running. A test deploys new code while a workflow is
  sending an email: that workflow finishes by email, one still waiting before the change and one started
  afterwards send an SMS, and the same deploy without the check blocks the first workflow with a mismatch.
- **Negative:** old code paths stay in the code until no running workflow uses them; the CLI and the history
  show which ones still do. A check placed after code that records no step, such as an untimed signal wait,
  sends a waiting workflow down the new path, which is usually what is wanted but must be understood.
- **Follow-ups:** `WorkflowReplayer` in the testkit checks new code against saved histories before a deploy.
