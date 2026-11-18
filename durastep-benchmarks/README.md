# Durastep Benchmarks

Reproducible measurements of Durastep's throughput, step latency and timer accuracy. Not released.

```bash
make bench                                    # everything, about 3 minutes
make bench-throughput ARGS="--workflows 20000"
make bench-latency ARGS="--rate 100 --seconds 120"
make bench-scaling                            # 1, 2 and 3 engine processes, and backlog sizes; about 10 minutes
make bench-replay                             # replay cost with JMH, about 2 minutes
```

The results and what they mean are in [docs/benchmarks.md](../docs/benchmarks.md).

Each run starts its own Postgres in a container (`postgres:16-alpine`, default settings), connects through a
HikariCP pool and runs one engine whose activities do nothing, so the numbers are the engine's own overhead.
Results go to `target/benchmarks/results.md` and `results.json`.

## What is measured

| Scenario | Method |
|---|---|
| Throughput | **Drain test.** Queue a number of three-step workflows with no engine running, start one engine, and time first start to last finish. This measures the engine's maximum, not how fast workflows can be started. |
| Latency | **Open loop.** Start workflows at a fixed rate, half the measured maximum by default, whatever the engine is doing, so a slow engine cannot hide its slowness by slowing the load. The first seconds are a warm-up and are not counted. |
| Timers | During the latency run, sleeps of 1 to 10 seconds; lateness is the time a timer fired minus the time it was due. |
| Scaling | The drain test with 1, 2 and 3 engines, each in its own process. The engines are warmed up on a smaller batch first, and only completions after the measured batch is queued count. |
| Backlog | The same drain with one engine and growing backlogs, to show whether queue size matters. |
| Replay | JMH, without a database: one full replay of a finished history of 10 to 10,000 steps. |

## Where the time goes

The scaling scenario also explains its numbers from Postgres itself: the statements that took the most time
(`pg_stat_statements`), commits per workflow, what busy connections were waiting on and which locks they waited
for, sampled every 100 ms, Postgres's CPU from `docker stats`, and the CPU each engine process used.

Latencies come from the timestamps the engine records in the history, all from Postgres's clock:

- **Step:** from a step being scheduled to the next step being scheduled, or the workflow finishing. One
  decision, one activity and the hand-offs between them.
- **Activity:** from a step being scheduled to its result arriving.
- **Workflow:** from start to finish.

## Options

`--workflows`, `--rate`, `--seconds`, `--warmup`, `--sleepers`, `--backlog`, `--pool`,
`--workflow-concurrency`, `--activity-concurrency`, `--image` and `--out`.
