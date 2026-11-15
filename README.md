# Durastep

**A lightweight, durable workflow engine for Java.**

Write business processes as plain code, and they survive crashes, restarts and failures, completing exactly
as intended.

[![CI](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml/badge.svg)](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **v0.1: the core works and is crash-tested.** Workflows run, survive a killed process and resume exactly.
> It is not ready for production yet: see [Limits of v0.1](#limits-of-v01) and the [roadmap](#roadmap).

```java
public final class OrderWorkflow implements Workflow<Order, Receipt> {
  @Override
  public Receipt run(WorkflowContext ctx, Order order) {
    OrderActivities shop = ctx.activities(OrderActivities.class);
    String chargeId = shop.chargeCard(order.id(), order.cents());
    shop.reserveStock(order.id());
    shop.sendEmail(order.id());
    return new Receipt(chargeId, ctx.now());
  }
}
```

**Proof:** a separate process is killed with `kill -9` in the middle of this workflow, and a second process
resumes it: the card is charged once, and the interrupted step runs again with the same idempotency key. This
test runs in CI on every commit, against Postgres 16 and the latest Postgres.

## The problem

Business processes span several steps and several systems:

1. Charge the customer's card.
2. Reserve the stock.
3. Send the confirmation email.

If the server crashes after step 1, the process is left half-done. Running it again from the start charges the
card a second time. Most teams patch this with status columns, retry jobs and cleanup scripts, and every
process ends up with its own fragile version.

Durastep lets you write the whole process as ordinary Java code. The engine records each step durably and,
after any failure, resumes exactly where it stopped, without repeating the steps that already succeeded.

## How it works

```mermaid
flowchart LR
    caller([Your code]) -->|start / wait for result| api

    subgraph app["Your application (any number of instances)"]
        api[Durastep client] --> store
        worker[Worker] -->|claim tasks| store
        worker --> wf[Workflow runtime<br/>replays history, decides next step]
        worker --> act[Activity runtime<br/>does the real work]
        store[Store<br/>one transaction per step]
    end

    store <--> pg[(PostgreSQL)]
```

- **Workflows are plain Java.** A workflow is a normal method that calls activities. Activities do the real
  work: API calls, database writes, emails.
- **History and replay.** Every step's result is saved to an append-only history. After a crash, another
  process replays the workflow from its history and continues with the next step.
- **Postgres is the only moving part.** Durastep is a library inside your application. There is no engine
  server: all instances coordinate through one Postgres database.

## Using it

```java
DurastepEngine engine = DurastepEngine.builder()
    .dataSource(dataSource)
    .workflow(OrderWorkflow.class, OrderWorkflow::new)
    .activities(OrderActivities.class, new ShopActivities(paymentApi))
    .build();
engine.start();

WorkflowHandle<Receipt> order = engine.client().start(OrderWorkflow.class, "order-42", input);
Receipt receipt = order.result(Duration.ofSeconds(30));
```

Inside an activity, pass the idempotency key to the system you call, so a repeated call is ignored:

```java
public String chargeCard(String orderId, long cents) {
  return paymentApi.charge(orderId, cents, ActivityContext.current().idempotencyKey());
}
```

Durastep is not on Maven Central yet. Build it with `./mvnw install` and depend on
`io.github.yanivks-pro:durastep-core:0.1.0`. The engine creates its tables in a `durastep` schema on start.

## Features in v0.1

- **Workflows as plain Java**, with activities declared as ordinary interfaces.
- **Durable history and replay:** finished steps return their saved result and are never run again.
- **Start by business ID:** starting `order-42` twice returns the same workflow.
- **At-least-once activities with idempotency keys**, such as `order-42/activity-2`.
- **One transaction per step and fencing tokens:** a crash never leaves half a step, and a worker that lost its
  lease cannot write.
- **Deterministic time and randomness:** `ctx.now()`, `ctx.random()` and `ctx.randomUUID()` return the same
  values on every replay.
- **Mismatch detection:** if new code disagrees with a running workflow's history, Durastep stops instead of
  guessing.
- **Results:** `result(timeout)` returns the value, throws `WorkflowFailedException`, or times out while the
  workflow carries on.

## Limits of v0.1

- **No retries or timers yet.** A failing activity fails the workflow. Retries, timeouts and durable timers
  come in M2.
- **One worker loop per instance**, polling every 100 ms. Concurrency settings, `NOTIFY` wake-ups and
  multi-instance proofs come in M3.
- **A workflow whose code no longer matches its history** is retried quietly after each lease instead of being
  flagged as BLOCKED; that arrives in M2.
- **No CLI, metrics or Spring Boot starter yet** (M5), and no Maven Central release.

## Design highlights

Every significant decision is recorded as an [Architecture Decision Record](docs/adr/README.md).

- **Embedded library, no server.** Adopting it means one dependency and one schema, not a new cluster to run.
  [ADR-0002](docs/adr/0002-embedded-library.md)
- **Postgres only.** Correctness can only be proven on the database you actually run.
  [ADR-0003](docs/adr/0003-postgres-only.md)
- **Java 25 and virtual threads.** Simple blocking code with high concurrency, and no reactive libraries.
  [ADR-0004](docs/adr/0004-java-25-virtual-threads.md)
- **One build, strict quality gates.** Error Prone, warnings as errors, enforced formatting and architecture
  rules on every build. [ADR-0005](docs/adr/0005-maven-multi-module.md)
- **Plain JDBC, at most five dependencies.** Every query is visible and reviewable.
  [ADR-0006](docs/adr/0006-plain-jdbc-minimal-dependencies.md)
- **Full replay from a pure runtime.** Replay is a function of history, tested in milliseconds without a
  database. [ADR-0008](docs/adr/0008-event-history-and-full-replay.md),
  [ADR-0009](docs/adr/0009-pure-workflow-runtime.md)
- **At least once, made safe.** Exactly-once side effects are impossible in principle; stable idempotency
  keys make repeats harmless. [ADR-0010](docs/adr/0010-at-least-once-with-idempotency-keys.md)
- **One writer to history.** A race found in design review was fixed with an inbox before any code existed.
  [ADR-0012](docs/adr/0012-single-writer-to-history.md)
- **The database guards the rules.** Constraints reject invalid states, such as a second decision task for the
  same workflow or a finished workflow without a finish time. [ADR-0007](docs/adr/0007-schema-migrations-with-flyway.md)

## Build from source

You need **Java 25** and **Docker** (the tests run against a real Postgres in a container).

```bash
git clone https://github.com/yanivks-pro/durastep.git
cd durastep
./mvnw verify
```

`./mvnw verify` checks formatting, compiles with Error Prone, runs the unit, architecture, Postgres
integration, crash and process-kill tests, and fails if line coverage drops below 85%.

## Roadmap

| Milestone | What it delivers | Status |
|---|---|---|
| M0 Foundations | Build, CI, database schema, first design decisions | ✅ Done |
| M1 Core idea (v0.1) | Kill it mid-workflow, restart, and it resumes exactly | ✅ Done |
| M2 Reliability (v0.2) | Retries, timeouts and durable timers that can sleep for days | 🚧 Next |
| M3 Distributed (v0.3) | Many instances, leases and fencing; chaos test with 0 lost and 0 duplicates | ⏳ Planned |
| M4 Interaction (v0.4) | Signals, cancellation, sagas and parallel steps | ⏳ Planned |
| M5 Operability (v0.5) | CLI, metrics and Grafana, Spring Boot starter | ⏳ Planned |
| M6 Safe evolution (v0.6) | Versioning, replay testing of new code, payload encryption | ⏳ Planned |
| M7 Showcase (v1.0) | Dashboard, tracing, benchmarks, complete documentation | ⏳ Planned |

## Non-goals

Durastep deliberately does not try to be:

- **A multi-database engine.** It relies on Postgres features and is tested on Postgres only.
- **A standalone server with SDKs for other languages.** It is a Java library.
- **A visual (BPMN) designer.** Workflows are code.
- **An exactly-once system without idempotency keys.** That is impossible in principle; Durastep gives every
  activity a stable key instead.
- **Multi-tenant or multi-region.** Those are jobs for your platform and for Postgres replication.

## License

Durastep is licensed under the [Apache License 2.0](LICENSE).
