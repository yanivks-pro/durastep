# Operations

Running Durastep in production: how to deploy and size it, what to watch, and how to fix workflows when
something goes wrong. Durastep runs inside your application, so most of this is about your application's
instances and its Postgres database.

## Deploying

- **Every instance can run an engine.** Instances find each other only through Postgres: add or remove them at
  any time, and the work spreads over whoever is running.
- **The schema migrates itself.** On start, an engine creates or upgrades its tables in the `durastep` schema
  with Flyway. To keep schema changes out of your application's hands, apply them from a deploy job and start the
  application's engines with `applyMigrations(false)` (`durastep.apply-migrations: false` in Spring Boot); the
  [security guide](security.md#use-a-dedicated-database-user) has the exact database setup.
- **Rolling deploys are safe.** Engines on the old and new versions can run side by side. If the new version
  changes a workflow's steps, protect running workflows with `ctx.version(...)` and check the change against saved
  histories before deploying ([writing workflows](writing-workflows.md#changing-code-safely)).
- **Shutting down hands work over.** `engine.stop()`, or Spring's graceful shutdown, stops claiming work, lets
  work in progress finish for up to the grace period (30 seconds by default, 20 in Spring Boot), and releases the
  rest so another engine takes it at once.

## Sizing

| Setting | Default | What it controls |
|---|---|---|
| `workflowConcurrency` | 10 | Decisions one engine runs at once; they are short, mostly database work |
| `activityConcurrency` | 100 | Activities one engine runs at once, each on a virtual thread |
| `leaseDuration` | 30 s | How long a crashed engine's decisions wait before another engine takes them |
| `activityQueues` | `default` | Which activity queues this engine serves |
| `shutdownGrace` | 30 s | How long `stop()` waits for work in progress |

- **Connections.** An engine borrows a connection for each short transaction, never while an activity runs, and
  keeps one more for notifications. A pool of 10 to 30 connections per instance is plenty for most loads; it
  never needs one connection per running activity.
- **Throughput.** On a laptop one engine runs about 160 to 200 three-step workflows a second and three engines
  about 230; Postgres's log writes are the limit after that ([benchmarks](../benchmarks.md)). Scale out with more
  instances, and up with a faster disk for Postgres.
- **The `tasks` table changes constantly.** If large backlogs are normal for you, let autovacuum run more often on
  it, since dead rows slow down claiming work as the backlog grows.

## Watching

With `durastep-micrometer`, or the Spring Boot starter and a `MeterRegistry`, the engine records:

| Meter | What it tells you |
|---|---|
| `durastep.workflows.started`, `durastep.workflows.finished` | Workflows started and finished, by type and final state |
| `durastep.workflows.running`, `durastep.workflows.blocked` | How many run and how many are blocked, from the database |
| `durastep.tasks.ready`, `durastep.tasks.oldest.age` | Work waiting for an engine, and how long the oldest has waited |
| `durastep.activities`, `durastep.decisions` | How long they take, and activity attempts by outcome |
| `durastep.timers.lateness` | How late timers fire |
| `durastep.leases.expired`, `durastep.results.rejected` | Work taken over from crashed or frozen engines, and the late results rejected |

The gauges count the whole database, so every engine reports the same value: take the largest, not the sum.
`observability/` has a ready-made Grafana dashboard.

**Alert on** blocked workflows above zero, and on the age of the oldest waiting work growing past what your
workflows can tolerate; that means engines are missing, overloaded or cannot reach the database.

**Logs** carry the workflow ID, activity and attempt in the MDC on every engine line, and never workflow data.
**Traces**, with `durastep-opentelemetry`, put each workflow's activities, timers and signals into the trace of the
request that started it. **The dashboard**, `durastep dashboard`, shows workflows by state, what waits, and each
workflow's steps over time.

## Fixing workflows

The [CLI](../../durastep-cli/README.md) works straight on the database, so it works even when no application is
running. Every command that changes a workflow asks for confirmation, and records who acted and why in its
history.

| Situation | What to do |
|---|---|
| Something is blocked | `durastep list --blocked`, then `durastep show <id>` for the error and the timeline |
| A bug blocked workflows, and the fix is deployed | Nothing: they retry with a backoff. To retry now: `durastep retry-now --all-blocked --reason "fixed in 1.4.2"` |
| A workflow should stop at once, without cleanup | `durastep terminate <id> --reason "…"` |
| A workflow should stop and clean up | `durastep cancel <id>` |
| A finished workflow must run again with the same input | `durastep rerun <id> --reason "…"` |
| A workflow waits for a person who answered elsewhere | `durastep signal <id> <name> '<json>'` |

The same commands exist on `DurastepClient`, for building them into your own tools.

## Keeping the database small

- **Retention** deletes finished workflows and their history 30 days after they closed, in small batches and on
  one engine at a time; running workflows are never deleted. Change it with `retention(...)` or turn it off with
  `noRetention()`.
- **Size limits** reject workflow data larger than 256 KB with a clear message, and block a history at 50,000
  events after a warning at 10,000.

## Known limits

- **PgBouncer in transaction mode** does not pass notifications through. Behind it, engines still work but find
  new work by polling once a second.
- **Backups** are ordinary Postgres backups: the database holds the complete state of every workflow.
