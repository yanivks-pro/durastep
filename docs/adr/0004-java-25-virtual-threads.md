# ADR-0004: Java 25 and virtual threads

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** C-03, NFR-20

## Context

Workflows and activities spend most of their time waiting: on HTTP calls, on the database, or on results that
are not yet in history. The engine must run many of them at once while keeping workflow code as plain,
blocking Java that is easy to read and to replay.

The history of a workflow is a fixed set of event types, and the engine must handle every one of them.

## Decision

Durastep requires Java 25 (LTS) and uses only stable language features, with no preview features.

- **Virtual threads** run workflows and activities. A concurrency limit, not a thread pool size, controls how
  much runs at once.
- **Records, sealed interfaces and pattern matching** model history events, so the compiler reports any event
  type that a `switch` does not handle.

## Alternatives considered

- **Java 21 (LTS).** It has virtual threads, but a virtual thread inside a `synchronized` block pins its
  carrier thread, which can starve the engine when libraries such as JDBC drivers block there. Java 24 removed
  this limitation (JEP 491), and Java 25 is the first LTS release with the fix.
- **Java 17 (LTS).** Widest reach, but no virtual threads. The engine would need reactive code or large thread
  pools, and workflow code would get harder to read.
- **Kotlin with coroutines.** Good concurrency model, but it moves the project away from plain Java, which is
  the main audience.

## Consequences

- **Positive:** simple blocking code throughout, and high concurrency without reactive libraries. The event
  model is checked by the compiler.
- **Negative:** applications still on Java 17 or 21 cannot adopt Durastep until they upgrade. Some build tools
  lag behind new Java releases, so their versions are pinned and checked in CI.
- **Follow-ups:** the Maven enforcer rejects builds on Java versions below 25
  ([ADR-0005](0005-maven-multi-module.md)).
