# ADR-0016: BLOCKED versus FAILED

- **Status:** Accepted
- **Date:** 2026-11-15
- **Requirements:** FR-08, FR-11, FR-13, FR-27

## Context

A workflow can stop making progress for two very different reasons.

- **A business outcome:** a card is declined after every retry, or the workflow decides on purpose that an
  order cannot be shipped. Ending the workflow is the correct result.
- **A bug or a deployment mistake:** a `NullPointerException`, code that no longer matches the history of a
  running workflow, or a workflow type that this version of the application does not know. Ending the
  workflow would turn a fixable bug into lost business: the order is gone, although a fix deployed an hour
  later would have completed it.

## Decision

FAILED is reserved for business outcomes. Everything else blocks the workflow until it is fixed.

| What stops the workflow code | Result |
|---|---|
| An `ActivityFailedException` it does not catch | **FAILED** |
| A `WorkflowFailedException` thrown on purpose | **FAILED** |
| A deadline from `WorkflowOptions.timeout` | **FAILED** with `WorkflowTimeoutException` |
| Any other exception, a `NonDeterminismException`, or an unknown workflow type | **RUNNING and BLOCKED** |

- BLOCKED is a flag on a running workflow, not a fifth state. The error is saved in `blocked_error` with its
  type, message and stack trace.
- A blocked decision writes nothing to history. Results that arrived meanwhile stay in the inbox.
- The decision is tried again after 1 second, doubling up to 5 minutes, without limit. The first block is
  logged as an error with its stack trace; later attempts log one line each.
- The first decision that succeeds clears the flag, so a fixed workflow continues by itself.
- A workflow deadline is a timer with sequence number 0, which no history event uses. It needs no schema
  change and is removed when the workflow finishes.

## Alternatives considered

- **Fail on every unexpected exception.** Simple and visible, but a bad deployment would permanently fail
  every workflow it touched.
- **Retry blocked workflows at a fixed short interval.** Recovers quickly, but floods logs and the database
  while a fix takes hours.
- **Pause blocked workflows until an operator resumes them.** Safe, but needs a person for every fix; an
  explicit `retry-now` command for impatient operators comes with the CLI (M5) instead.

## Consequences

- **Positive:** a bug never loses a workflow. Deploying a fix is enough to resume everything it blocked. The
  saved stack trace shows where the code failed.
- **Negative:** a workflow with a bug that is never fixed stays RUNNING forever. Operators need to see blocked
  workflows: the CLI, metrics and dashboard (M5, M7) show them, and the partial index on `blocked` keeps
  that query fast.
- **Found while building it:** the first version marked a recovered workflow COMPLETED before clearing its
  blocked flag. The schema rule "blocked only while RUNNING" rejected the change, so the bug could not
  corrupt data; the flag is now cleared in the same statement.
