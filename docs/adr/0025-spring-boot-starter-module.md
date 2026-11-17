# ADR-0025: A Spring Boot starter module

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** FR-38, FR-41

## Context

Many Java teams build their services with Spring Boot. For them, wiring an engine by hand means a builder, a
list of registrations kept in sync with their classes, and code to start and stop it at the right moments. The
core must stay free of Spring ([ADR-0002](0002-embedded-library.md), [ADR-0006](0006-plain-jdbc-minimal-dependencies.md)),
so Spring support has to live in its own module, and it must not change the versions the other modules use.

## Decision

A single module, `durastep-spring-boot-starter`, holds both the starter and its auto-configuration.

- **Registration by annotation.** `@WorkflowDefinition` makes a workflow a prototype-scoped bean, so every run
  gets a fresh instance with its dependencies injected. `@ActivityImplementation` makes an ordinary bean the
  implementation of every interface it implements, or of the interfaces it names.
- **Settings as properties.** `durastep.*` covers the schema, concurrency, queues, lease, shutdown grace,
  payload and history limits, and retention, including `retention: off`. `durastep.worker.enabled=false` gives
  only a client, for applications that start or watch workflows that others run.
- **The application's lifecycle.** The engine uses the application's `DataSource`, starts once the context is
  ready, and stops in Spring's graceful shutdown, so work in progress finishes or is handed to other engines.
  Its default grace, 20 seconds, fits within Spring's default of 30 seconds per shutdown phase.
- **Optional extras.** With Spring Boot's health support present, a health indicator reports whether the
  database answers, with running and blocked workflows and the age of the oldest waiting work. With
  `durastep-micrometer` and a `MeterRegistry` present, the metrics are wired in, alongside any `EngineListener`
  beans of the application.
- **Versions stay ours.** The Spring Boot dependency list is imported only in this module. Versions the project
  pins itself take precedence; `jackson-annotations` is pinned too, because Spring Boot's list chose an older
  version than Jackson 3.2.4 needs, which broke the engine at start.

## Alternatives considered

- **Separate `autoconfigure` and `starter` modules**, the pattern of Spring's own projects. It helps when other
  starters reuse the auto-configuration; for one library it is one more module with no benefit.
- **Registration through builder customizer beans.** Flexible, but every application would repeat the same
  lookups that annotations make unnecessary.
- **Spring Boot's dependency list in the parent POM.** Simpler, but it would move the versions of every module,
  including those that have nothing to do with Spring.

## Consequences

- **Positive:** a Spring Boot application gets durable workflows with two annotations and a few properties, and
  health and metrics without code. Tests start real application contexts against Postgres, including client-only
  mode, the optional extras with and without their libraries, invalid settings, and a shutdown that lets a running
  activity save its result.
- **Negative:** the module follows Spring Boot's release cadence, so each Spring Boot upgrade needs a check that
  its dependency list still agrees with the versions Durastep pins.
