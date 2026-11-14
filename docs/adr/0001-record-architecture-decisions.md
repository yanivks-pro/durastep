# ADR-0001: Record architecture decisions as ADRs

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** Q-06

## Context

Durastep is built around a few decisions that are not obvious from the code: why replay instead of snapshots,
why Postgres is the only store, why delivery is at-least-once. Without a written record, the reasoning behind
these choices is lost, and future changes risk undoing them by accident. Readers of the repository, such as
contributors and reviewers, need the "why", not only the "what".

## Decision

Every significant decision is recorded as an Architecture Decision Record (ADR) in `docs/adr/`, using
[the template](0000-template.md).

- One file per decision, named `NNNN-short-slug.md`, numbered in order.
- At most one page: Status, Context, Decision, Alternatives considered, Consequences.
- An ADR is written in the same change that turns the decision into code.
- Accepted ADRs are never rewritten. A changed decision gets a new ADR, and the old one is marked
  "Superseded by ADR-NNNN".
- [The index](README.md) lists every ADR with its status.

## Alternatives considered

- **Decisions in the README or a single design document.** Easy to start, but the reasoning gets edited over
  time and the history of why something changed is lost.
- **Decisions in pull request descriptions only.** The reasoning exists, but it is hard to find and is not
  versioned with the code.
- **No written decisions.** Fastest in the short term; the cost appears later as repeated debates and
  accidental regressions.

## Consequences

- **Positive:** the reasoning behind the design is versioned with the code and easy to find. Reviewers can
  judge trade-offs, not just results.
- **Negative:** each significant change carries a small writing cost.
- **Follow-ups:** ADR-0002 to ADR-0006 record the decisions already made for the foundations (M0).
