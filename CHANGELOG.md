# Changelog

All notable changes to Durastep are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Activity retries: `ctx.activities(Interface.class, ActivityOptions)` with a `RetryPolicy` (attempts,
  backoff that doubles up to a cap with jitter, non-retryable errors), a limit per attempt and an optional
  limit for all attempts together. Retries are delayed tasks that survive crashes and reuse the same
  idempotency key.
- `ActivityFailedException` in workflow code when an activity fails for good; workflows may catch it.
- `ActivityTimeoutException` for attempts that run longer than their limit.

### Changed

- A failing activity is now retried under its policy (default: 5 attempts, 1 second doubling up to 1 minute)
  instead of failing the workflow at once. A workflow fails only if it does not catch the final
  `ActivityFailedException`, and its failure then names the activity and the number of attempts.
- While an activity runs, its lease is extended to its attempt limit plus one lease, so a slow attempt is not
  taken over by another worker. A crashed activity is therefore resumed after that time.

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
