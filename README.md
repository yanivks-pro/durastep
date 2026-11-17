# Durastep

**A lightweight, durable workflow engine for Java.**

Write business processes as plain code, and they survive crashes, restarts and failures, completing exactly
as intended.

[![CI](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml/badge.svg)](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **v0.4: workflows that wait for people, run steps in parallel and undo their work.** They survive chaos too: in
> a test that kills engines at random, nothing is lost and nothing is done twice. It is not ready for production
> yet: see [Limits of v0.4](#limits-of-v04) and the [roadmap](#roadmap).

```java
public final class OrderWorkflow implements Workflow<Order, Receipt> {
  @Override
  public Receipt run(WorkflowContext ctx, Order order) {
    OrderActivities shop = ctx.activities(OrderActivities.class, RETRY_FIVE_TIMES);
    String chargeId;
    try {
      chargeId = shop.chargeCard(order.id(), order.cents());
    } catch (ActivityFailedException declined) {
      throw new WorkflowFailedException("payment failed: " + declined.failureMessage());
    }
    shop.reserveStock(order.id());
    ctx.sleep(Duration.ofDays(3));
    shop.sendReviewRequest(order.id());
    return new Receipt(chargeId, ctx.now());
  }
}
```

**Proof:** a chaos test runs 1,000 workflows on 3 engine processes while killing one at random with `kill -9`
100 times and freezing one 11 times. All 1,000 workflows complete with the right result, **0 are lost and 0
effects happen twice**, and the database passes every invariant check. It runs every night on GitHub; the
latest report is attached to each [nightly run](https://github.com/yanivks-pro/durastep/actions/workflows/nightly.yml).

```
completed 1,000 of 1,000 · lost 0 · duplicate effects 0 · kills 100 · freezes 11
zombie results rejected 110 · repeats absorbed by idempotency keys 211 · invariant violations 0
```

Focused tests prove each guarantee on its own, on every commit:

- A separate process is killed with `kill -9` in the middle of a workflow, and a second process resumes it:
  the card is charged once, and the interrupted step runs again with the same idempotency key.
- A workflow sleeping through an engine shutdown is woken by the next engine.
- A bug blocks a workflow with its stack trace saved; deploying the fix resumes it.
- A worker frozen with `kill -STOP` loses its task; when it wakes up, its result is rejected by the fencing
  check.
- A ten-second database outage in the middle of a load loses nothing and repeats nothing.
- Parallel branches replay identically: 300 random orders of results, each replayed from every decision point,
  give exactly the decisions that were recorded.
- A process killed with `kill -9` while a saga undoes a trip is replaced, and every booking is undone exactly
  once.

These run in CI on every commit, against Postgres 16 and the latest Postgres.

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

Give activities retries and time limits, and give a workflow a deadline:

```java
static final ActivityOptions RETRY_FIVE_TIMES = ActivityOptions.builder()
    .attemptTimeout(Duration.ofSeconds(10))
    .retryPolicy(RetryPolicy.builder()
        .maxAttempts(5)
        .initialBackoff(Duration.ofSeconds(1))
        .nonRetryable(CardDeclinedException.class)
        .build())
    .build();

client.start(OrderWorkflow.class, "order-42", input, WorkflowOptions.timeout(Duration.ofDays(7)));
```

Inside an activity, pass the idempotency key to the system you call, so a repeated call is ignored:

```java
public String chargeCard(String orderId, long cents) {
  return paymentApi.charge(orderId, cents, ActivityContext.current().idempotencyKey());
}
```

Wait for people with signals, and tell them where things stand with a status:

```java
ctx.setStatus("waiting for the manager");
Optional<Decision> decision = ctx.awaitSignal("decision", Decision.class, Duration.ofDays(2));

client.signal("expense-7", "decision", new Decision(true, "Grace", "fine"));
client.handle("expense-7", ExpenseWorkflow.class).describe().status();
```

Run steps in parallel, and undo the ones that succeeded when a later one fails or the workflow is cancelled:

```java
Saga saga = ctx.saga();
saga.addCompensation(() -> trips.cancelFlight(trip.id()));
saga.addCompensation(() -> trips.cancelHotel(trip.id()));
Promise<String> flight = ctx.async(() -> trips.bookFlight(trip));
Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
try {
  Promise.allOf(flight, hotel).get();
} catch (ActivityFailedException | CancelledException e) {
  saga.compensate();
  throw e;
}

client.cancel("trip-9");
```

Durastep is not on Maven Central yet. Build it with `./mvnw install` and depend on
`io.github.yanivks-pro:durastep-core:0.4.0`. The engine creates its tables in a `durastep` schema on start.

## Try the examples

Three small applications show these features with scripted scenarios, printing what happens as it happens. You
need Docker and Java 25:

```bash
docker compose up -d --wait
./mvnw -q install -Dmaven.test.skip=true
./mvnw -q -pl durastep-examples exec:java -Dexample=trip -Dscenario=no-cars
```

- **Order processing:** a flaky payment gateway is retried, and the card is charged once.
- **Trip booking:** flight and hotel in parallel, then a car; when no car is left, or the traveller cancels, a
  saga undoes every booking.
- **Expense approval:** waits for the manager's decision, escalates to the director when nobody answers in time,
  and can be withdrawn.

See [the examples](durastep-examples/README.md) for every scenario and sample output.

## Features in v0.4

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
- **Retries:** a failing activity is retried with a backoff that doubles up to a cap, with jitter, and every
  attempt uses the same idempotency key. Errors such as a declined card can be marked as not worth retrying.
- **Timeouts:** a limit per attempt, an optional limit for all attempts together, and an optional deadline for
  the whole workflow.
- **Durable timers:** `ctx.sleep(Duration.ofDays(3))` survives restarts and wakes up about half a second after
  it is due, on any engine.
- **Failures that workflow code can handle:** an activity that fails for good throws `ActivityFailedException`,
  which the workflow may catch; `WorkflowFailedException` fails the workflow on purpose.
- **BLOCKED instead of lost:** a bug, a mismatch between code and history, or an unknown workflow type leaves
  the workflow running and blocked with the error saved, and it resumes once a fix is deployed.
- **Results:** `result(timeout)` returns the value, throws `WorkflowFailedException`, or times out while the
  workflow carries on.
- **Many engines, one decider per workflow:** each engine runs up to `workflowConcurrency` decisions and
  `activityConcurrency` activities at once and never claims more than it has room for; a workflow is never
  decided by two engines at the same time.
- **Instant wake-ups:** Postgres notifications wake idle engines as soon as there is work; polling once a second
  is the safety net.
- **Heartbeats:** long activities call `ActivityContext.current().heartbeat()`; a crashed or frozen engine's
  work is taken over after the heartbeat timeout, and a zombie learns it lost the task.
- **Named queues:** `ActivityOptions.queue("gpu")` routes activities to the engines that serve that queue.
- **Graceful shutdown and outages:** `stop()` lets work finish within a grace period and hands the rest to other
  engines at once; a database outage pauses work with one warning and resumes it by itself.
- **Signals:** `client.signal(id, name, payload)` sends data to a running workflow, and
  `ctx.awaitSignal(name, type[, timeout])` waits for it. Signals that arrive early are kept, each is received
  exactly once, and a request ID makes retried sends safe.
- **Published status:** `ctx.setStatus(text)` records where a workflow stands; `describe()` returns it with the
  workflow's state.
- **Cooperative cancellation:** `client.cancel(id)` makes the workflow throw `CancelledException` at its next
  wait point, so it can clean up with ordinary activities before it ends CANCELLED.
- **Sagas:** `ctx.saga()` collects undo steps and runs them in reverse order; every one runs even if another
  fails.
- **Parallel branches:** `ctx.async(...)` returns a `Promise`, and `Promise.allOf` and `Promise.anyOf` combine
  them. Branches' activities run at the same time, while their code takes turns and receives results in
  history order, so replay stays exact.

## Limits of v0.4

- **Only the main workflow code can wait for signals**, not code in parallel branches.
- **No CLI, metrics or Spring Boot starter yet** (M5), so blocked workflows are visible only in the database,
  and there is no Maven Central release.
- **No workflow versioning yet** (M6): changing the steps of a workflow while instances of it are running makes
  them blocked with a mismatch.
- **Notifications do not pass through PgBouncer in transaction mode**; behind it, engines fall back to polling
  once a second.

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
- **Retries are delayed tasks.** No retry scheduler and no extra table: a failed attempt is the same task, made
  visible again later. [ADR-0015](docs/adr/0015-retries-as-delayed-tasks.md)
- **BLOCKED, not FAILED, for bugs.** A bad deployment pauses workflows instead of failing them; a fix resumes
  them. [ADR-0016](docs/adr/0016-blocked-versus-failed.md)
- **Postgres is the task queue.** `SKIP LOCKED` claims and notifications sent only on commit, with polling as the
  safety net. [ADR-0017](docs/adr/0017-postgres-as-the-task-queue.md)
- **Leases, fencing tokens and heartbeats.** Crashed work is taken over; late results from frozen workers are
  rejected. [ADR-0018](docs/adr/0018-leases-fencing-and-heartbeats.md)
- **One decider per workflow.** A unique index and leases keep each workflow sequential while many run in
  parallel. [ADR-0019](docs/adr/0019-one-decider-per-workflow.md)
- **Signals in, published status out.** Signals go through the inbox like every other change; a status the
  workflow publishes replaces on-demand queries. [ADR-0020](docs/adr/0020-published-status-instead-of-queries.md)
- **Cooperative cancellation.** The workflow is asked to stop, not killed, so it can clean up; the moment it
  learns of it comes from the history. [ADR-0021](docs/adr/0021-cooperative-cancellation.md)
- **Parallel steps on one logical thread.** Each branch numbers its own steps and receives results in history
  order; delivering them in turn order instead breaks replay in 142 of 300 random runs.
  [ADR-0022](docs/adr/0022-parallel-steps-on-one-logical-thread.md)
- **The database guards the rules.** Constraints reject invalid states, such as a second decision task for the
  same workflow or a finished workflow without a finish time. [ADR-0007](docs/adr/0007-schema-migrations-with-flyway.md)

## Build from source

You need **Java 25** and **Docker** (the tests run against a real Postgres in a container).

```bash
git clone https://github.com/yanivks-pro/durastep.git
cd durastep
./mvnw verify
```

`./mvnw verify` checks formatting, compiles with Error Prone, runs the unit, architecture and Postgres
integration tests, including the crash, process-kill, zombie and outage tests, and fails if line coverage drops
below 85%. It also runs every [example](durastep-examples/README.md) scenario end to end. `./mvnw verify -Pchaos`
runs the chaos test, which takes a few minutes.

## Roadmap

| Milestone | What it delivers | Status |
|---|---|---|
| M0 Foundations | Build, CI, database schema, first design decisions | ✅ Done |
| M1 Core idea (v0.1) | Kill it mid-workflow, restart, and it resumes exactly | ✅ Done |
| M2 Reliability (v0.2) | Retries, timeouts and durable timers that can sleep for days | ✅ Done |
| M3 Distributed (v0.3) | Many instances, leases and fencing; chaos test with 0 lost and 0 duplicates | ✅ Done |
| M4 Interaction (v0.4) | Signals, cancellation, sagas, parallel steps and runnable examples | ✅ Done |
| M5 Operability (v0.5) | CLI, metrics and Grafana, Spring Boot starter | 🚧 Next |
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
