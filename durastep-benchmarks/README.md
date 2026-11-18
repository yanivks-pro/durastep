# Durastep Benchmarks

Reproducible measurements of Durastep's throughput, step latency and timer accuracy. Not released.

```bash
make bench                                    # everything, about 3 minutes
make bench-throughput ARGS="--workflows 20000"
make bench-latency ARGS="--rate 100 --seconds 120"
```

Each run starts its own Postgres in a container (`postgres:16-alpine`, default settings), connects through a
HikariCP pool and runs one engine whose activities do nothing, so the numbers are the engine's own overhead.
Results go to `target/benchmarks/results.md` and `results.json`.

## What is measured

| Scenario | Method |
|---|---|
| Throughput | **Drain test.** Queue a number of three-step workflows with no engine running, start one engine, and time first start to last finish. This measures the engine's maximum, not how fast workflows can be started. |
| Latency | **Open loop.** Start workflows at a fixed rate, half the measured maximum by default, whatever the engine is doing, so a slow engine cannot hide its slowness by slowing the load. The first seconds are a warm-up and are not counted. |
| Timers | During the latency run, sleeps of 1 to 10 seconds; lateness is the time a timer fired minus the time it was due. |

Latencies come from the timestamps the engine records in the history, all from Postgres's clock:

- **Step:** from a step being scheduled to the next step being scheduled, or the workflow finishing. One
  decision, one activity and the hand-offs between them.
- **Activity:** from a step being scheduled to its result arriving.
- **Workflow:** from start to finish.

## Options

`--workflows`, `--rate`, `--seconds`, `--warmup`, `--sleepers`, `--pool`, `--workflow-concurrency`,
`--activity-concurrency`, `--image` and `--out`.
