# ADR-0028: A read-only dashboard on the JDK's own web server

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** FR-43

## Context

The CLI answers precise questions well, but some questions are easier to answer by looking: how many workflows
are blocked right now, how much work is waiting, which workflows of a type failed this morning. Grafana shows
the numbers over time, not the workflows themselves. A small web view of the workflows helps, as long as it does not become a second, weaker CLI, a
security risk, or a front-end project with its own toolchain.

## Decision

A separate module, `durastep-dashboard`, serves a few server-rendered pages, and only shows.

- **The JDK's built-in HTTP server** (`com.sun.net.httpserver`) on virtual threads, and HTML written in Java with
  one stylesheet. No web framework, no template engine, no JavaScript and no npm, so the module adds no
  dependencies beyond the core.
- **It reads through the public client** (`DurastepClient`): `list`, `stats` and two small additions,
  `countByState()` and `WorkflowQuery.idStartsWith(...)`. It never writes SQL of its own, so the CLI, the
  dashboard and applications see the same data the same way.
- **Read-only.** Pages answer `GET` and `HEAD`; everything else is refused. All actions stay in the CLI, which
  asks for confirmation and records who acted and why ([ADR-0023](0023-operator-commands-work-directly-on-postgres.md)).
- **Safe by default.** It listens on the loopback address unless told otherwise, and the CLI warns when it is.
  Every piece of text from a workflow or from the address bar is HTML-escaped; a strict Content Security Policy
  forbids scripts, inline styles, frames and forms that post elsewhere. A database error shows a plain message,
  with the details only in the log.
- **Started from the CLI or embedded:** `durastep dashboard` uses the CLI's connection settings;
  `DurastepDashboard.start(client, port)` runs it inside an application.

## Alternatives considered

- **Spring MVC or another web framework.** Familiar, but it would pull a framework into every user of the
  dashboard and tie it to that framework's releases, for three pages.
- **A single-page application** (React or similar). Richer interaction, but a second language, a build
  toolchain and a JSON API to secure, for a view that shows tables.
- **Actions in the dashboard** (retry, terminate). Convenient, but a web page that changes workflows needs
  authentication, CSRF protection and an audit trail of its own; the CLI already has the last and needs neither
  of the first two.

## Consequences

- **Positive:** anyone with database access can see the state of their workflows in a browser with one command,
  and nothing on the page can change them. Tests run the pages against Postgres, including workflow IDs and
  statuses that contain HTML, filters that do, a database that refuses the connection and a port already in
  use.
- **Negative:** no login, so the dashboard must stay on the loopback address or behind something that adds one.
  Lists show at most the newest 100 matches, with no paging. Pages refresh by reloading, not live.
