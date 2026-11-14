# ADR-0002: Embedded library, no engine server

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** C-01, FR-37, FR-41, FR-42

## Context

A durable workflow engine needs somewhere to keep state and something to coordinate work between machines.
Established engines such as Temporal run as a separate cluster that applications talk to over the network.
That model scales well, but every team that adopts it must also deploy, secure, upgrade and monitor another
distributed system before running its first workflow.

Most Java services already run several instances against a shared Postgres database. That database can hold
the state and coordinate the instances.

## Decision

Durastep is a library: a JAR added to the application. There is no engine server. Every application instance
runs the engine in-process, and all instances coordinate only through a shared Postgres database.

- Applications create the engine with `DurastepEngine.builder()` and their own `DataSource`.
- Operator tools (the CLI and the read-only dashboard) also connect straight to Postgres.
- A client-only mode lets an application start and query workflows without running workers.

## Alternatives considered

- **Standalone engine server with a client SDK** (the Temporal model). Language-neutral and centrally managed,
  but it adds a network hop to every step and a second system to operate, which is too heavy for the target
  scale.
- **Sidecar process next to each application.** Isolates the engine from the application, but it still has
  to be deployed and monitored, and it brings back inter-process communication.
- **Library with a broker (Kafka or RabbitMQ) for coordination.** Fast fan-out, but it splits state across
  two systems, and keeping them consistent is the hard problem a workflow engine exists to solve.

## Consequences

- **Positive:** adoption is one dependency and one database schema. Each step is local calls plus one
  transaction. Scaling out means running more application instances.
- **Negative:** Java only; no SDKs for other languages. Engine upgrades ship with application deployments, so
  the schema must support old and new versions side by side (FR-32). Postgres capacity limits total
  throughput.
- **Follow-ups:** ADR-0003 (Postgres only) and the M3 ADRs on using Postgres as a task queue with leases and
  fencing.
