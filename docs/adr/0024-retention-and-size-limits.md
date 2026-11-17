# ADR-0024: Retention and size limits

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** FR-34, FR-35, FR-36

## Context

Durastep keeps everything in Postgres: every workflow, every step of its history, every input and result. Left
alone, that data only grows. Finished workflows pile up for ever, a single large payload is written again with
every step that carries it, and a workflow that loops without end builds a history that every decision must
replay. None of this fails at once; it makes the database and the engines slower until something does.

## Decision

The engine bounds all three, with defaults that suit most applications and settings on the builder.

- **Retention.** A background job deletes workflows that finished more than 30 days ago (`retention`), with their
  history, tasks, timers and inbox entries, through the schema's cascading foreign keys. Running workflows are
  never deleted. A rerun outlives its original; only its `rerun_of` link becomes empty. The job runs shortly
  after an engine starts and then every ten minutes, deletes at most 20 batches of 500 per run, using the index on
  `closed_at`, and takes a Postgres advisory lock for each batch, so only one engine deletes at a time and the
  others skip their turn. `noRetention()` keeps everything, for example for audits.
- **Payload limit.** Every piece of workflow data is checked when it is converted to JSON; above 256 KB
  (`maxPayloadSize`) it throws `PayloadTooLargeException` with a tip to pass a reference instead. Where the data
  comes from decides the outcome: a caller of `start` or `signal` gets the exception and nothing is stored; an
  activity whose result is too large fails without retrying, so workflow code can handle it; oversized activity
  arguments or workflow results are bugs in workflow code, so the workflow is blocked
  ([ADR-0016](0016-blocked-versus-failed.md)).
- **History limit.** A workflow logs one warning when its history reaches 10,000 events and is blocked at 50,000
  (`historyLimits`), with a message that says what to do: split the work, and terminate or rerun this workflow
  ([ADR-0023](0023-operator-commands-work-directly-on-postgres.md)).

## Alternatives considered

- **Retention by partitioning the tables by time.** Dropping a whole partition is the cheapest way to delete, but
  partitions by creation time would also hold running workflows, and partitioning complicates every query and
  every unique constraint.
- **Leaving cleanup to the operator.** One less moving part, but every team would write the same script, and
  most would forget to.
- **Failing a workflow whose history is too long.** Ends the problem, but loses a workflow that may be close to
  finishing; blocking keeps every choice open.
- **Compressing or offloading large payloads automatically.** Hides the problem instead of pointing to the fix,
  and adds a storage system to run.

## Consequences

- **Positive:** the database stays the size of the last month of work, deletes never come in large waves, and
  oversized data and runaway histories are reported with a clear next step. Tests cover each case; with the
  advisory lock ignored on purpose, the test for one engine at a time fails.
- **Negative:** finished workflows disappear after 30 days unless retention is changed, so anything needed longer
  must be copied elsewhere or kept with `noRetention()`. Workflows that need very long histories must be split.
- **Follow-ups:** metrics for deleted workflows and blocked workflows come with the metrics module.
