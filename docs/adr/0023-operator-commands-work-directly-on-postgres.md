# ADR-0023: Operator commands work directly on Postgres

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** FR-28, FR-29, FR-39, FR-41, FR-42

## Context

Operators need to step in when things go wrong: retry a blocked workflow once a fix is deployed, stop one that
must not continue, or run a finished one again. These are the moments when the normal path does not work. A
blocked workflow's decider fails every time it runs, so a command that waits for the decider, as signals and
cancellation do ([ADR-0012](0012-single-writer-to-history.md)), would never take effect. Operators also need these
commands without running the application, from a CLI, and every manual action must leave a record of who did it
and why.

## Decision

Operator commands are transactions on the database, available through the public client, which can run without
workers. They write to the history themselves.

- `retryNow`, `terminate`, `retryAllBlocked` and `rerun` take a required reason, and the client records who acted:
  the operating system user, or the name given with `actingAs`. Each command adds an `OPERATOR_ACTION` event with
  the action, the operator and the reason. Replay skips these events.
- `terminate` adds the audit event and `WORKFLOW_CANCELLED`, deletes the workflow's tasks, timers and inbox
  entries, and stores the reason, which `WorkflowCancelledException.reason()` returns.
- `retryNow` adds the audit event and makes the blocked decision visible at once, with its backoff reset.
- `rerun` starts a new workflow, `<id>-rerun-<n>`, with the original's type and input, linked through `rerun_of`.
  Its audit event goes into the new workflow's history, so the original's stays closed.
- Two rules keep writes by operators safe next to deciders. They take the same locks in the same order as a
  decision: first the workflow's decision task, then the workflow row
  ([ADR-0019](0019-one-decider-per-workflow.md)). And they increment the decision task's fencing token, or delete
  the task, so a decider that was in the middle of a step cannot save it
  ([ADR-0018](0018-leases-fencing-and-heartbeats.md)); it decides again on the new history.
- The CLI uses the same client, so it shares these code paths and needs only a database connection.

## Alternatives considered

- **Commands through the inbox, like signals.** Keeps a single writer to history, but needs a working decider,
  which is exactly what a blocked workflow lacks.
- **An admin endpoint inside the application.** Needs the application to be up and reachable, adds a network
  API to secure, and still needs a database path for when it is down.
- **Audit records in a separate table.** Keeps histories pure, but splits a workflow's story in two places, and
  the timeline in the CLI would have to merge them.

## Consequences

- **Positive:** a stuck workflow can be fixed with one command, whether or not its decider can run, and its
  history says who did what and why. A test shows that a decider in the middle of a step is rejected after
  `retryNow` and after `terminate`; with the fencing removed on purpose, that test fails.
- **Negative:** history now has a second kind of writer, so every new operator command must follow the two rules
  above. `terminate` skips cleanup by design; `cancel` remains the polite way to stop a workflow.
- **Follow-ups:** the CLI (FR-42) exposes these commands with confirmation prompts and `--json` output.
