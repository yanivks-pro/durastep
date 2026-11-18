# ADR-0029: How Durastep is benchmarked

- **Status:** Accepted
- **Date:** 2026-11-18
- **Requirements:** NFR-06, NFR-07, NFR-08, NFR-09, NFR-10

## Context

Durastep promises a throughput, a step latency, timer accuracy and gains from adding engines. Benchmarks of
queue-like systems are easy to get flattering by accident: a closed-loop load generator slows down when the
system does and hides its worst moments, a cold JVM or an empty database changes the numbers, and two clocks on
two machines disagree. The numbers also need to explain themselves: a throughput that does not say what limits it
cannot be improved or trusted.

## Decision

Benchmarks live in their own module, `durastep-benchmarks`, which is not released, and follow these rules.

- **One reproducible setup.** Every run starts its own `postgres:16-alpine` with default settings, plus
  `pg_stat_statements`, connects through a HikariCP pool as applications do, and uses activities that do nothing,
  so the numbers are the engine's own overhead. Commits stay durable (`synchronous_commit` on). `make bench` and a
  manual GitHub workflow run the same code.
- **Throughput by draining a backlog.** Workflows are queued first and then the engine runs, so the result is the
  engine's maximum rather than the speed of the load generator. For several engines, each runs in its own process,
  warmed up on a smaller batch, and only completions after the measured batch was queued count.
- **Latency under an open-loop load.** Workflows start at a fixed rate, half the measured maximum, whatever the
  engine is doing, after a warm-up that is not counted. The harness reports how many starts were late, so an
  overloaded generator is visible.
- **One clock.** Latencies come from the timestamps the engine writes into the history, all from Postgres.
- **Explained from the database.** The scaling scenario records the statements that took the most time, commits
  per workflow, what busy connections were waiting on and which locks they waited for, sampled every 100 ms,
  Postgres's CPU and each engine's CPU.
- **Replay cost with JMH,** separately from the database, for histories of 10 to 10,000 steps.
- **Honest results.** A target that is missed is reported as missed, with its measured cause.

## Alternatives considered

- **A general load tool** such as Gatling or k6 against an HTTP front end. It would measure the front end too,
  and cannot see the history's timestamps or Postgres's waits.
- **Closed-loop latency** (start the next workflow when one finishes). Simpler, but it under-reports latency
  exactly when the system struggles.
- **Tuned Postgres settings.** Higher numbers, but no longer what a user gets by default, and easy to tune for the
  benchmark instead of for the workload.

## Consequences

- **Positive:** the first scaling run showed three engines only 8% faster than one, and its own statistics named
  the cause, Postgres's notify lock; the fix in [ADR-0031](0031-notify-after-commit.md) was measured the same way.
  Anyone can repeat the runs with one command.
- **Negative:** a laptop's numbers depend on the laptop; the GitHub runs give a second, slower machine to compare
  with. Container I/O on macOS is slower than on Linux, which favours conservative numbers.
