# Writing workflows

A workflow is the plan; activities do the work. This guide covers the rules workflow code must follow, and every
tool it has: retries and time limits, timers, signals, cancellation, sagas, parallel steps, and changing code
while workflows run. [Getting started](getting-started.md) shows the basic setup.

## Workflows and activities

| | Workflow | Activity |
|---|---|---|
| What it is | A class that implements `Workflow<I, O>` | An interface and its implementation |
| What it does | Decides what happens next | Talks to the world: APIs, databases, email |
| How often it runs | Again from the start at every step, against its history | Once per attempt, possibly more than once |
| What it must be | Deterministic | Idempotent, through its idempotency key |

Durastep runs the workflow's code again from the beginning every time something happens, and gives each call to
an activity, a timer or a signal the result it already had. That is how it resumes after a crash
([architecture](../architecture.md#replay-leases-and-fencing)). It only works if the code makes the same calls,
in the same order, every time.

## The rules for workflow code

| Don't | Do instead |
|---|---|
| Call APIs, databases, files or anything else outside the process | Put it in an activity |
| `Instant.now()`, `System.currentTimeMillis()`, `LocalDate.now()` | `ctx.now()` |
| `new Random()`, `Math.random()`, `UUID.randomUUID()` | `ctx.random()`, `ctx.randomUUID()` |
| `Thread.sleep(...)` | `ctx.sleep(duration)`, which survives restarts |
| Threads, executors, `CompletableFuture` | `ctx.async(...)` and `Promise` |
| Read static fields, environment variables or settings that can change | Pass them in the input, or read them in an activity |
| Rely on the order of a `HashMap` or `HashSet` when it decides which steps run | Use a `List`, `LinkedHashMap` or `TreeMap` |
| Catch `Throwable` or `Error` | Catch the exceptions you expect; Durastep uses an `Error` internally to pause the code |
| Change which steps run, or their order, while workflows are running | Use `ctx.version(...)`, [below](#changing-code-safely) |

Plain computation, branches and loops are fine, and so are values that come back from activities. Logging from
workflow code is allowed but repeats on every replay; publish where the workflow stands with
`ctx.setStatus(...)` instead.

If new code no longer matches a running workflow's history, Durastep does not guess: the workflow is **blocked**
with the mismatch saved, and it resumes when code that matches is deployed.

## Activities

Give each kind of activity its own limits:

```java
static final ActivityOptions PAYMENTS = ActivityOptions.builder()
    .attemptTimeout(Duration.ofSeconds(10))
    .totalTimeout(Duration.ofMinutes(5))
    .retryPolicy(RetryPolicy.builder()
        .maxAttempts(5)
        .initialBackoff(Duration.ofSeconds(1))
        .maxBackoff(Duration.ofMinutes(1))
        .nonRetryable(CardDeclinedException.class)
        .build())
    .build();

Shop shop = ctx.activities(Shop.class, PAYMENTS);
```

- **Retries** wait twice as long each time, up to `maxBackoff`, with some randomness. Errors listed as
  `nonRetryable` fail at once.
- **The idempotency key**, `ActivityContext.current().idempotencyKey()`, is the same for every attempt of a step,
  such as `order-42/activity-2`. Send it to the systems you call.
- **Heartbeats** let long activities be taken over quickly when an engine dies. Set a `heartbeatTimeout` and call
  `ActivityContext.current().heartbeat()` regularly; a heartbeat that finds the task taken over stops the activity
  before it repeats work another engine is doing.
- **Queues** route activities to the engines that serve them: `ActivityOptions.builder().queue("gpu")`, with
  `DurastepEngine.builder().activityQueues("gpu")` on those engines.

Activity arguments and results are stored as JSON, up to 256 KB. Pass references to large data, not the data.

## When things fail

| What happens | What the workflow sees | Where the workflow ends up |
|---|---|---|
| An activity fails after its retries, or with a non-retryable error | `ActivityFailedException`, which it may catch | Wherever its code goes next |
| The code throws `WorkflowFailedException` | | FAILED, on purpose |
| A bug: any other exception, or code that no longer matches the history | | **Blocked**: still running, with the error saved, retried with a backoff |

A blocked workflow resumes by itself once the cause is fixed and deployed; an operator can also retry it at once
([operations](operations.md#fixing-workflows)). Bugs never turn into failed workflows that need to be restarted
by hand.

## Time

```java
ctx.sleep(Duration.ofDays(3));
client.start(OrderWorkflow.class, "order-42", order, WorkflowOptions.timeout(Duration.ofDays(30)));
```

A sleep is a durable timer: it survives restarts and fires on any engine, usually within half a second of being
due. A workflow timeout fails the workflow if it has not finished by then.

## People and other systems

```java
ctx.setStatus("waiting for the manager");
Optional<Decision> decision = ctx.awaitSignal("decision", Decision.class, Duration.ofDays(2));

client.signal("expense-7", "decision", new Decision(true, "Grace", "fine"));
client.handle("expense-7").describe().status();
```

A signal that arrives before the workflow waits for it is kept, each is received once, and a request ID makes a
retried send safe: `client.signal(id, name, payload, requestId)`. The status is a short text the workflow
publishes; anyone can read it without disturbing the workflow.

## Cancellation and sagas

`client.cancel(id)` asks a workflow to stop. The next time it waits for something, it gets a
`CancelledException` and can clean up with ordinary activities before it ends CANCELLED. A saga collects the undo
steps and runs them in reverse order:

```java
Saga saga = ctx.saga();
try {
  String flight = trips.bookFlight(trip);
  saga.addCompensation(() -> trips.cancelFlight(trip.id()));
  String hotel = trips.bookHotel(trip);
  saga.addCompensation(() -> trips.cancelHotel(trip.id()));
  return new Booking(flight, hotel);
} catch (ActivityFailedException | CancelledException e) {
  saga.compensate();
  throw e;
}
```

Every compensation runs even if another fails.

## Parallel steps

```java
Promise<String> flight = ctx.async(() -> trips.bookFlight(trip));
Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
Promise.allOf(flight, hotel).get();
```

The branches' activities run at the same time, while their code takes turns, so replay stays exact.
`Promise.anyOf(...)` waits for the first. Only the workflow's main code can wait for signals.

## Changing code safely

Running workflows replay their history against the code you deploy. Adding, removing or reordering steps would
make that history no longer match, so keep the old path for workflows that already went past the change:

```java
if (ctx.version("send-sms-instead-of-email", 1) >= 1) {
  notifications.sms(order);
} else {
  notifications.email(order);
}
```

Workflows that reach the check for the first time get version 1; workflows that already went past it get 0. The
answer is recorded, so it never changes for a workflow. Remove the old path once no running workflow uses it.

Then check the change before you deploy it. Save a few real histories with `durastep history <id> --json` and
replay them against the new code with the [testkit](../../durastep-testkit/README.md):

```java
WorkflowReplayer.of(OrderWorkflow.class, OrderWorkflow::new)
    .replay(Path.of("src/test/resources/histories"));
```

It fails, naming each history the change would break.

## Long-running workflows

Every step replays the whole history, which costs about 5 µs per step: nothing for most workflows, but a
workflow with tens of thousands of steps spends seconds on each one ([benchmarks](../benchmarks.md#replay-cost)).
Durastep logs a warning when a history reaches 10,000 events and blocks it at 50,000. Split very long processes
into several workflows, for example one per month or per batch.
