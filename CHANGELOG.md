# Changelog

All notable changes to Durastep are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `DurastepClient.connect(dataSource)` for applications that start, signal or watch workflows without
  running workers.
- `client.list(WorkflowQuery)` filters workflows by state, type and blocked, newest first.
- `WorkflowHandle` can now `signal(...)` and `cancel()` its workflow, and `resultAsync()` waits without blocking.
- `WorkflowDescription` includes when the workflow started and when it finished.
- Operator commands with an audit trail: `handle.retryNow(reason)`, `handle.terminate(reason)`,
  `client.retryAllBlocked(reason)` and `client.rerun(id, reason)`. Each records who acted and why in the
  workflow's history; `client.actingAs(name)` sets who, by default the operating system user. A terminated
  workflow's `WorkflowCancelledException.reason()` says who terminated it and why.

### Fixed

- An activity result or a timer that arrived while its workflow was finishing could deadlock with the decision
  that finished it. Postgres aborted one of the two and the work was retried, so nothing was lost, but it
  showed up as errors. Both now take their locks in the same order as decisions.

## [0.4.0] - 2026-11-16

### Added

- Signals: `client.signal(id, name, payload)`, with an optional request ID that makes retried sends safe, and
  `ctx.awaitSignal(name, type[, timeout])` in workflow code. Signals that arrive early are kept until the
  workflow waits, and each one is received exactly once.
- Published status: `ctx.setStatus(text)` and `handle.describe()`, which returns a workflow's state, status and
  whether it is blocked.
- Cooperative cancellation: `client.cancel(id)`. The workflow receives a `CancelledException` at its next wait
  point, can clean up with ordinary activities, and ends CANCELLED; `result()` throws
  `WorkflowCancelledException`. Running activities are not interrupted, but their results are ignored.
- Sagas: `ctx.saga()` with `addCompensation(...)` after each step and `compensate()` to undo them in reverse
  order. Every undo step runs even if one fails, and the first failure is thrown at the end.
- Parallel branches: `ctx.async(...)` returns a `Promise`; branches' activities run at the same time, while
  their code takes turns on one logical thread and receives results in history order. Each branch numbers its
  own steps, which are stored with the branch's ID.
- `Promise.allOf(...)` and `Promise.anyOf(...)`; `anyOf` returns the branch that finished first in the history.
  Branches that only wait for each other block the workflow with a clear message.
- Examples module with three runnable, tested applications: order processing (retries, idempotency), trip
  booking (parallel branches, saga, cancellation) and expense approval (signals, status, timeouts, escalation).
  `docker-compose.yml` starts a local Postgres for them.

## [0.3.0] - 2026-11-16

### Added

- Concurrency: `workflowConcurrency` (default 10) and `activityConcurrency` (default 100) on the engine
  builder. Each engine claims at most as many tasks as it has free slots, in one batch, and runs each on its
  own virtual thread.
- Instant wake-ups: every commit that creates work sends a Postgres notification, and each engine listens on
  one connection, so idle engines start new work at once. The connection reconnects by itself if it breaks.
- Heartbeats: `ActivityOptions.heartbeatTimeout` and `ActivityContext.heartbeat()`. A long activity that
  heartbeats keeps its lease; if its engine crashes or freezes, it is taken over after the heartbeat timeout
  instead of its whole attempt timeout. A heartbeat from an attempt that was taken over throws
  `ActivityLeaseLostException`.
- Named queues: `ActivityOptions.queue("gpu")` sends activities to a queue, and
  `DurastepEngine.Builder.activityQueues(...)` chooses which queues an engine runs. Notifications wake only
  engines that serve the queue.
- Graceful shutdown: `stop()` stops claiming, gives work in progress `shutdownGrace` (default 30 seconds) to
  finish, then interrupts what is left and releases its leases, so other engines take it over at once.
- Database outages: engines pause with backoff from 1 to 30 seconds, log one warning when the database becomes
  unreachable and one message when it is back, and keep retrying to save activity results until their lease
  ends. A ten-second outage in the middle of a load loses nothing and repeats nothing.
- Chaos test: 1,000 workflows on 3 engines, 100 random `kill -9` and about 10 freezes, with a report of
  lost and duplicated work. It runs every night and on demand (`./mvnw verify -Pchaos`), not on every
  pull request.

### Changed

- `stop()` waits up to 30 seconds by default instead of 10 before giving up on work in progress.
- Engines look for work every second when nothing wakes them, instead of every 100 ms, and hold one extra
  connection for notifications. The Postgres driver is now a compile-time dependency.

## [0.2.0] - 2026-11-15

### Added

- Activity retries: `ctx.activities(Interface.class, ActivityOptions)` with a `RetryPolicy` (attempts,
  backoff that doubles up to a cap with jitter, non-retryable errors), a limit per attempt and an optional
  limit for all attempts together. Retries are delayed tasks that survive crashes and reuse the same
  idempotency key.
- `ActivityFailedException` in workflow code when an activity fails for good; workflows may catch it.
- `ActivityTimeoutException` for attempts that run longer than their limit.
- Durable timers: `ctx.sleep(Duration)` stores its wake-up time in Postgres, survives restarts, may last days, and is
  fired by any engine about half a second after it is due.
- Workflow deadlines: `client.start(..., WorkflowOptions.timeout(duration))`. A workflow still running at its
  deadline ends FAILED with `WorkflowTimeoutException`, even while an activity is running; finishing earlier
  removes the deadline.
- `WorkflowFailedException(message)` lets workflow code fail on purpose, for a business reason.
- BLOCKED workflows: a bug, a mismatch between code and history, or an unknown workflow type now leaves the
  workflow RUNNING with a `blocked` flag and the error, including its stack trace, saved. Nothing is written to
  history, the decision is retried after 1 second doubling up to 5 minutes, and the workflow continues by itself
  once a fix is deployed.

### Changed

- A failing activity is now retried under its policy (default: 5 attempts, 1 second doubling up to 1 minute)
  instead of failing the workflow at once. A workflow fails only if it does not catch the final
  `ActivityFailedException`, and its failure then names the activity and the number of attempts.
- While an activity runs, its lease is extended to its attempt limit plus one lease, so a slow attempt is not
  taken over by another worker. A crashed activity is therefore resumed after that time.
- An unexpected exception in workflow code no longer fails the workflow; it blocks it until the code is fixed.
  Only an uncaught `ActivityFailedException`, a `WorkflowFailedException` or a deadline end a workflow as
  FAILED.
- A workflow whose code no longer matches its history is now visibly BLOCKED instead of being retried quietly
  after each lease.

## [0.1.0] - 2026-11-15

### Added

- Workflows written as plain Java (`Workflow<I, O>`), with activities declared as interfaces and called
  through `ctx.activities(...)`.
- Durable, append-only history and full replay: finished steps return their saved result; a mismatch between
  code and history stops the workflow with `NonDeterminismException`.
- Deterministic `ctx.now()`, `ctx.random()` and `ctx.randomUUID()`.
- `DurastepEngine` builder with `dataSource`, `workflow`, `activities`, `schema`, `applyMigrations` and
  `leaseDuration`; the schema is applied with Flyway on start.
- `DurastepClient` with duplicate-safe `start` by business ID and `handle`; `WorkflowHandle.result(timeout)`
  returns the value, throws `WorkflowFailedException`, or throws `TimeoutException`.
- At-least-once activities with stable idempotency keys from `ActivityContext.current()`.
- One transaction per engine step, leases with fencing tokens, and an inbox so only the decider writes history.
- Crash tests at exact points inside the engine and a test that kills a separate process with `kill -9`
  mid-workflow and checks that the next process resumes it exactly.
- Coverage gate: the build fails below 85% line coverage across unit and integration tests.
- Release workflow: a `v*` tag publishes a GitHub release with notes from this changelog.
- Maven multi-module build on Java 25 with pinned versions, Error Prone, warnings as errors, Spotless
  formatting, JaCoCo coverage and ArchUnit architecture rules.
- Integration tests against a real Postgres through Testcontainers.
- GitHub Actions CI on Postgres 16 and the latest Postgres, with Dependabot updates after a 14-day cooldown.
- Dependencies are downloaded from Maven Central only.
- Database schema V1 with Flyway: `workflows`, `history_events`, `tasks`, `timers` and `inbox` in a
  dedicated `durastep` schema, with constraints for the core rules.
- Architecture Decision Records 0001 to 0014.
- README and the Apache 2.0 licence.
