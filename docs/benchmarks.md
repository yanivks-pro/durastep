# Benchmarks

What Durastep can do on ordinary hardware, how that was measured, what limits it, and how the measurements found
and fixed its biggest bottleneck. Everything here can be repeated with one command.

## How to run them

```bash
make bench            # throughput, latency and timers, about 3 minutes
make bench-scaling    # 1, 2 and 3 engines and growing backlogs, about 10 minutes
make bench-replay     # the cost of replaying histories, with JMH
```

Each run starts its own Postgres in a container with default settings, connects through a HikariCP pool and runs
engines whose activities do nothing, so the numbers are Durastep's own overhead. Commits are durable. The method,
and why it was chosen, is in [ADR-0029](adr/0029-benchmark-method.md) and the
[benchmarks module](../durastep-benchmarks/README.md).

**Machine:** a 2021 MacBook Pro, Apple M1 Pro with 8 cores and 16 GB, macOS 15.3; Postgres 16.16 in OrbStack
with 8 CPUs and 8 GB; Java 25.

## Results against the targets

| Requirement | Target | Measured | |
|---|---|---|---|
| NFR-06 Throughput | ≥ 200 three-step workflows/s on one engine | 159 to 198/s on one engine, depending on the backlog; 219/s with two | ❌ on one engine |
| NFR-07 Step latency | p50 < 10 ms, p99 < 50 ms | p50 15.9 ms, p99 26.6 ms for a whole step; p50 8.8 ms for an activity hand-off | ❌ p50, ✅ p99 |
| NFR-08 Timer accuracy | within 1 s | p99 505 ms, worst 527 ms | ✅ |
| NFR-09 Scaling | clear gains 1 → 2 → 3, bottleneck measured | 1.38× with two engines, 1.43× with three; Postgres's log writes | ✅ |
| NFR-10 Reproducible | load harness plus JMH, documented setup | `make bench`, `make bench-scaling`, `make bench-replay` | ✅ |

The two misses are explained [below](#what-limits-durastep-now), with what would close them.

## Throughput

Draining 10,000 queued three-step workflows with one engine: **157 workflows/s**, 471 steps/s.

| Engines, each in its own process | Workflows/s | Speed-up | Postgres CPU | Engine CPU |
|---|---|---|---|---|
| 1 | 159 | 1.00× | 2.2 cores | 0.8 cores |
| 2 | 219 | 1.38× | 3.2 cores | 1.1 cores |
| 3 | 228 | 1.43× | 3.7 cores | 1.2 cores |

## Latency under load

78 workflows/s, half the measured maximum, started at a steady rate for 60 seconds after a warm-up; no start was
late.

| Measure (ms) | p50 | p90 | p99 | Max |
|---|---|---|---|---|
| A step: scheduled → the next step scheduled | 15.9 | 19.1 | 26.6 | 267.2 |
| An activity: scheduled → its result arrived | 8.8 | 10.9 | 14.0 | 243.6 |
| A workflow: started → finished | 55.4 | 62.3 | 79.3 | 326.7 |
| A timer: fired after it was due | 251.4 | 461.4 | 505.1 | 526.5 |

Timers are found by a poller that runs every 500 ms, which is where their lateness comes from.

## How the benchmarks found and fixed a bottleneck

The first scaling run was disappointing: three engines were only 8% faster than one.

| Engines | Workflows/s | Busy Postgres time spent waiting for one lock |
|---|---|---|
| 1 | 138 | 62% |
| 2 | 145 | 86% |
| 3 | 149 | 90% |

The harness samples what busy Postgres connections are waiting on, and which locks: almost all of it was
`object on pg_database, AccessExclusiveLock`. Postgres takes that lock when a transaction that sent a `NOTIFY`
commits and keeps it until the commit is on disk. Durastep notified inside every transaction that created work,
so those commits queued up behind each other's disk writes, while Postgres used about two of its eight cores.

**First fix, rejected.** Skip the notification when other work is already waiting, since busy workers poll again
anyway. The lock disappeared and three engines reached 238/s, but under a steady load tasks piled up while idle
workers slept until their safety poll: the median step went from 12 ms to over a second.

**Second fix, kept.** Send the notification right after the commit, in a short transaction of its own. That
transaction writes nothing, so it holds the lock for microseconds, and every new task still wakes the engines
([ADR-0031](adr/0031-notify-after-commit.md)).

| | Before | After |
|---|---|---|
| One engine | 138/s | 159/s |
| Three engines | 149/s (1.08×) | 228/s (1.43×) |
| Waiting for the notify lock | 62% to 90% | none |
| Step latency p50 / p99 | 12.1 / 20.6 ms | 15.9 / 26.6 ms |

The fix costs one round trip per hand-off, a few milliseconds of latency, for a large gain in throughput. The
chaos test, 1,000 workflows through 100 `kill -9`, still loses and repeats nothing.

## What limits Durastep now

- **Durable commits.** With three engines, Postgres's busiest wait is writing its log to disk (`LWLock:
  WALWrite`, 56%). A three-step workflow takes about 48 commits: every decision and every activity claims its
  task, loads what it needs, and saves its result in separate transactions, and the read-only ones count too.
  Fewer transactions per step, such as loading a task's data in the same statement that claims it, is the most
  promising next step for both throughput and latency.
- **The backlog.** With the notify lock gone, the size of the backlog shows: one engine drains 2,000 queued
  workflows at 198/s and 20,000 at 138/s, because the claim query slows from 0.5 to 2.5 ms as the `tasks` table
  collects dead rows faster than autovacuum removes them.

| Workflows queued | Workflows/s, one engine | Claim query, mean |
|---|---|---|
| 2,000 | 198 | 0.47 ms |
| 5,000 | 183 | 0.97 ms |
| 10,000 | 159 | 1.71 ms |
| 20,000 | 138 | 2.51 ms |

- **Step latency.** A step is two hand-offs, to an activity worker and back to a decider, each a few commits and a
  notification. The activity hand-off alone meets the 10 ms target at the median; the whole step does not.

## Replay cost

Every decision replays the workflow's whole history ([ADR-0008](adr/0008-event-history-and-full-replay.md)), so
its cost matters for long workflows. `make bench-replay` measures one full replay with JMH, without a database,
for workflows of 10 to 10,000 steps.

| Steps in the history | One full replay |
|---|---|
| 10 | 57 µs |
| 100 | 0.54 ms |
| 1,000 | 5.1 ms |
| 10,000 | 52 ms |

Replay is linear, about 5 µs per step. Because each decision replays everything before it, a workflow's total
replay work grows with the square of its length: about 27 ms of CPU over the whole life of a 100-step workflow,
2.5 seconds for 1,000 steps and over 4 minutes for 10,000. This is why the engine warns when a history reaches
10,000 events and blocks it at 50,000 ([ADR-0024](adr/0024-retention-and-size-limits.md)): long-running work
belongs in several smaller workflows.
