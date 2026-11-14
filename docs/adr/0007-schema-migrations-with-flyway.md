# ADR-0007: Schema migrations with Flyway

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-30, FR-31, FR-32, FR-34

## Context

Durastep keeps its state in five tables inside the user's own Postgres database
([ADR-0003](0003-postgres-only.md)). The schema has to be created on first start, upgraded when Durastep is
upgraded, and stay compatible while old and new application versions run side by side during a rolling
deployment. Many applications already use a migration tool for their own tables, and the two must not
interfere.

## Decision

The schema is managed by Flyway with versioned SQL scripts that ship inside the `durastep-core` JAR.

- Scripts live in `durastep/db/migration`, not in Flyway's default `db/migration`, so an application's own
  Flyway setup never picks them up.
- All tables, and Flyway's history table, live in a dedicated `durastep` schema. The scripts never name the
  schema, so the schema name can be configured.
- The database enforces the core rules itself: one decider task per workflow, one task per activity, a
  workflow is closed if and only if it is in a final state, and `rerun_of` is cleared rather than cascaded,
  so deleting an old workflow never deletes its rerun.
- `V1` may still change until the v0.1 release, because no real database has run it. From v0.1 on, released
  scripts are never edited: every change is a new versioned script, following expand-then-contract.

## Alternatives considered

- **Liquibase.** Equally capable, but changes are usually written in XML or YAML rather than the SQL that
  correctness depends on.
- **Hand-rolled migration runner.** No dependency, but it would have to solve locking, checksums and history
  that Flyway already handles.
- **Tables created by the application's own tooling.** Leaves upgrades to every user and makes the schema
  drift between installations.

## Consequences

- **Positive:** the schema is plain, reviewable SQL. Upgrades run automatically, and invalid states are
  rejected by the database even if engine code has a bug.
- **Negative:** Flyway adds two artifacts (`flyway-core` and `flyway-database-postgresql`), counted as one of
  the five core dependencies ([ADR-0006](0006-plain-jdbc-minimal-dependencies.md)).
- **Follow-ups:** in M1 `DurastepEngine.start()` applies migrations by default, with an option to turn this
  off so DBAs can run the scripts themselves.
