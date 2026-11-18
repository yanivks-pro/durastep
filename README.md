# Durastep

**A lightweight, durable workflow engine for Java.**

Write business processes as plain code, and they survive crashes, restarts and failures, completing exactly
as intended.

[![CI](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml/badge.svg)](https://github.com/yanivks-pro/durastep/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **v0.6: safe to change.** Deploy new workflow code while workflows are running, check it against saved
> histories in CI first, and encrypt workflow data before it reaches the database. All on top of an engine you can
> operate and that survives chaos: in a test that kills engines at random, nothing is lost and nothing is done
> twice. It is not ready for production yet: see [Limits of v0.6](#limits-of-v06) and the [roadmap](#roadmap).

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

**Throughput:** on a laptop, with durable commits and default Postgres settings, one engine runs **159 to 198
three-step workflows a second** and three engines **228**; a step takes 16 ms at the median and 27 ms at the 99th
percentile. The [benchmarks](docs/benchmarks.md) show how this was measured, which targets are not met yet and
why, and how they found Postgres's notify lock capping three engines at 1.08 times one, and the fix that took
them to 1.43.

Focused tests prove each guarantee on its own, on every commit:

- A separate process is killed with `kill -9` in the middle of a workflow, and a second process resumes it:
  the card is charged once, and the interrupted step runs again with the same idempotency key.
- A workflow sleeping through an engine shutdown is woken by the next engine.
- A bug blocks a workflow with its stack trace saved; deploying the fix resumes it.
- A worker frozen with `kill -STOP` loses its task; when it wakes up, its result is rejected by the fencing
  check.
- A ten-second database outage in the middle of a load loses nothing and repeats nothing.
- Replay is checked with property tests: hundreds of random mixes of results, timers, signals, cancellations and
  crashed decisions must end correctly and replay every decision identically; a failing case shrinks to the
  smallest one.
- A process killed with `kill -9` while a saga undoes a trip is replaced, and every booking is undone exactly
  once.
- New code is deployed while a workflow is sending an email: that workflow finishes by email, and newer ones
  send an SMS.
- A card number and a PIN pass through an encrypted workflow, and appear in neither the database nor the logs.

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
`io.github.yanivks-pro:durastep-core:0.6.0`, or on `durastep-spring-boot-starter` in a Spring Boot application.
The engine creates its tables in a `durastep` schema on start.

## Try it in one command

You need only Docker:

```bash
git clone https://github.com/yanivks-pro/durastep.git && cd durastep
docker compose up -d --wait
open http://127.0.0.1:8099
```

This builds Durastep and starts Postgres, three engines, a load of orders, trips, expense claims and reports, the
read-only dashboard, Prometheus and Grafana on [http://127.0.0.1:3000](http://127.0.0.1:3000). Then break it:

```bash
demo/chaos.sh
```

It kills engines with `kill -9` and freezes them while they work, then waits for the started workflows to finish
and checks that none was lost and no effect happened twice. The [demo guide](demo/README.md) explains the report.

## Try the examples

Three small applications show these features with scripted scenarios, printing what happens as it happens. You
need Docker and Java 25:

```bash
docker compose up -d --wait postgres
./mvnw -q install -Dmaven.test.skip=true
./mvnw -q -pl durastep-examples exec:java -Dexample=trip -Dscenario=no-cars
```

- **Order processing:** a flaky payment gateway is retried, and the card is charged once.
- **Trip booking:** flight and hotel in parallel, then a car; when no car is left, or the traveller cancels, a
  saga undoes every booking.
- **Expense approval:** waits for the manager's decision, escalates to the director when nobody answers in time,
  and can be withdrawn.

See [the examples](durastep-examples/README.md) for every scenario and sample output.

## Operate it

The [CLI](durastep-cli/README.md) talks straight to the database, so it works whether or not your application
is running:

```text
$ durastep list --blocked
ID          TYPE           STATE    BLOCKED  STARTED              STATUS
order-1042  OrderWorkflow  RUNNING  yes      2026-11-17 10:02:11  charging the card

$ durastep show order-1042                 # the error that blocks it, and a readable timeline
$ durastep retry-now --all-blocked --reason "fix deployed in 1.4.2"
$ durastep terminate order-1042 --reason "fraud"
```

Every command can print JSON. Commands that change workflows ask for confirmation and record who acted and why
in the workflow's history; the same commands exist on the client: `retryNow`, `terminate`, `retryAllBlocked` and
`rerun`.

To follow one workflow through the rest of your system, [`durastep-opentelemetry`](durastep-opentelemetry/README.md)
puts its activity attempts, timers, signals and end into the trace of the request that started it.

To look rather than ask, `durastep dashboard` serves a read-only [dashboard](durastep-dashboard/README.md) on
`http://127.0.0.1:8099/`: workflows by state, blocked workflows, waiting work, a list to search and filter, and a
page per workflow with a chart of its steps and its history.

To watch it, the [one-command demo](#try-it-in-one-command) runs Prometheus and Grafana on
[http://127.0.0.1:3000](http://127.0.0.1:3000). The **Durastep** dashboard shows workflows started and finished, running and blocked workflows, waiting work and
its age, activity attempts by outcome, latencies, timer lateness and takeovers. Every engine log line carries the
workflow ID, activity and attempt in the MDC.

## Spring Boot

With the [starter](durastep-spring-boot-starter/README.md), two annotations and a few properties are enough:

```java
@WorkflowDefinition
class OrderWorkflow implements Workflow<Order, Receipt> { … }   // a fresh instance per run, with injection

@ActivityImplementation
class ShopActivities implements Payments, Warehouse { … }      // registered for every interface
```

The engine uses the application's `DataSource`, stops during Spring's graceful shutdown, reports to
`/actuator/health`, records its metrics when a `MeterRegistry` is present, and traces workflows when an
`OpenTelemetry` bean and [`durastep-opentelemetry`](durastep-opentelemetry/README.md) are present.

## Change it safely

Running workflows replay their history against the new code after every deploy, so changing their steps needs
care. Keep the old path for workflows that already went past a change:

```java
if (ctx.version("send-sms-instead-of-email", 1) >= 1) {
  shop.sms(order);          // workflows that reach this point from now on
} else {
  shop.email(order);        // workflows that already went past it
}
```

Then prove it before the deploy: save a few real histories with `durastep history <id> --json` and replay them
against the new code in a unit test with the [testkit](durastep-testkit/README.md):

```java
WorkflowReplayer.of(OrderWorkflow.class, OrderWorkflow::new)
    .replay(Path.of("src/test/resources/histories"));   // fails, naming each history the change would break
```

Encrypt workflow data before it reaches the database, with keys you control and can rotate:

```java
DurastepEngine.builder()
    .payloadCodec(AesGcmPayloadCodec.withKeys(Map.of("2026-11", key), "2026-11"))
```

The [security guide](docs/security.md) covers the rest: a database user with the exact privileges Durastep needs,
TLS, keys and rotation, logs and retention.

## Features in v0.6

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
- **Client API:** `DurastepClient.connect(dataSource)` works without workers; `list(WorkflowQuery)` filters by
  state, type and blocked; handles signal, cancel, wait with `resultAsync()`, and show their history.
- **Operator commands with an audit trail:** `retryNow`, `terminate`, `retryAllBlocked` and `rerun` record who
  acted and why in the workflow's history, and work even on blocked workflows.
- **Limits:** data larger than 256 KB is rejected with a tip; a history logs a warning at 10,000 events and is
  blocked at 50,000.
- **Retention:** finished workflows are deleted 30 days after they closed, in small batches and on one engine at
  a time; running workflows are never deleted.
- **Observability:** the workflow ID, activity and attempt in every engine log line; an `EngineListener` for
  events; `client.stats()`; Micrometer meters in `durastep-micrometer`; a Grafana dashboard.
- **CLI:** `list`, `show`, `history`, `stats`, `signal`, `cancel`, `terminate`, `retry-now` and `rerun`, as one
  runnable JAR or a Docker image.
- **Spring Boot starter:** annotations, `durastep.*` properties, graceful shutdown, health, metrics and a
  client-only mode.
- **Versioning:** `ctx.version(changeId, maxVersion)` keeps the old code path for workflows that already went
  past a change; the choice is recorded in the history and does not shift step numbers.
- **Testkit:** `WorkflowReplayer` replays saved histories against new code in a unit test, and a finished
  workflow must still end the same way.
- **Payload encryption:** `PayloadCodec` and `AesGcmPayloadCodec` encrypt inputs, arguments, results and
  signals, with key rotation; engines, clients, the testkit and the starter take the codec, and the CLI shows
  `[encrypted]`.
- **Security guide:** a tested database setup for a least-privilege user, and a test that keeps workflow data
  out of the logs.

## Limits of v0.6

- **Only the main workflow code can wait for signals**, not code in parallel branches.
- **No Maven Central release and no published CLI image yet**: build the JARs and the image from source.
- **Replay compares which steps run and in what order, not their arguments**, both in the engine and in the
  testkit.
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
- **Operator commands work directly on Postgres.** Retry, terminate and rerun work even when a workflow's decider
  keeps failing; they take the same locks as decisions and fence out a decider in the middle of a step.
  [ADR-0023](docs/adr/0023-operator-commands-work-directly-on-postgres.md)
- **Retention and size limits.** Old finished workflows are deleted in small batches under an advisory lock;
  oversized data and runaway histories are stopped with a clear next step.
  [ADR-0024](docs/adr/0024-retention-and-size-limits.md)
- **A read-only dashboard without a web framework.** The JDK's own HTTP server and HTML written in Java, with no
  JavaScript; it reads through the public client and cannot change anything.
  [ADR-0028](docs/adr/0028-read-only-dashboard.md)
- **One trace per workflow.** The caller's trace context is stored with the workflow, so every attempt, timer
  and signal joins the request that started it, on any engine and days later; the core has no tracing
  dependency. [ADR-0030](docs/adr/0030-one-trace-per-workflow.md)
- **A Spring Boot starter in its own module.** Spring's dependency versions stay out of the core and the other
  modules. [ADR-0025](docs/adr/0025-spring-boot-starter-module.md)
- **Versioning with markers in the history.** A version check records its answer without becoming a step, so
  adding one never shifts the steps of a running workflow. [ADR-0026](docs/adr/0026-versioning-markers.md)
- **Pluggable payload encryption.** Data is encrypted in the application, with the key named in each envelope
  and authenticated; names and errors stay readable so tools keep working.
  [ADR-0027](docs/adr/0027-pluggable-payload-encryption.md)
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
below 85% in any module. It also runs every [example](durastep-examples/README.md) scenario end to end, checks
the CLI's output against recorded snapshots, and starts real Spring Boot applications. `./mvnw verify -Pchaos`
runs the chaos test, which takes a few minutes. `make bench` runs the
[benchmarks](durastep-benchmarks/README.md) on a Postgres of their own.

| Module | What it is |
|---|---|
| `durastep-core` | The engine, plain Java |
| `durastep-testkit` | `WorkflowReplayer` for testing new code against saved histories |
| `durastep-micrometer` | Micrometer metrics |
| `durastep-dashboard` | A read-only web dashboard |
| `durastep-opentelemetry` | One OpenTelemetry trace per workflow |
| `durastep-cli` | The command-line tool |
| `durastep-spring-boot-starter` | Spring Boot 4 integration |
| `durastep-examples` | Runnable examples and the load generator; not released |
| `durastep-benchmarks` | Reproducible benchmarks, `make bench`; not released |

## Roadmap

| Milestone | What it delivers | Status |
|---|---|---|
| M0 Foundations | Build, CI, database schema, first design decisions | ✅ Done |
| M1 Core idea (v0.1) | Kill it mid-workflow, restart, and it resumes exactly | ✅ Done |
| M2 Reliability (v0.2) | Retries, timeouts and durable timers that can sleep for days | ✅ Done |
| M3 Distributed (v0.3) | Many instances, leases and fencing; chaos test with 0 lost and 0 duplicates | ✅ Done |
| M4 Interaction (v0.4) | Signals, cancellation, sagas, parallel steps and runnable examples | ✅ Done |
| M5 Operability (v0.5) | CLI, metrics and Grafana, operator commands, retention, Spring Boot starter | ✅ Done |
| M6 Safe evolution (v0.6) | Versioning, replay testing of new code, property tests, payload encryption | ✅ Done |
| M7 Showcase (v1.0) | Dashboard, tracing, benchmarks, complete documentation | 🚧 Next |

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
