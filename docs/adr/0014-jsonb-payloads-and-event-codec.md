# ADR-0014: JSONB payloads and an explicit event codec

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** FR-06, FR-35, NFR-17

## Context

Workflow inputs, activity arguments and results are user data of any shape. They must be stored durably,
readable by operator tools, and decodable by future versions of Durastep for as long as a workflow's history
is kept. History events also carry engine fields, such as which activity a result belongs to.

## Decision

- **Payloads are JSON**, converted with Jackson, and stored in `jsonb` columns.
- **History events are encoded by an explicit codec**, not by automatic serialisation of the Java records.
  Each event has a stable `event_type` name (`ACTIVITY_COMPLETED`) and a documented JSON shape
  (`{"scheduledSeq": 2, "result": ...}`). The codec has a test for every event type and for the stored shape.
- Values round-trip, not formatting: `jsonb` stores the meaning of the JSON, so spacing and key order may
  change between writing and reading. Tests compare decoded values, not JSON text.

## Alternatives considered

- **Automatic serialisation of the event records.** Less code, but renaming a Java field would silently change
  the stored format and break decoding of existing histories.
- **`text` or `json` columns.** Keep the exact text, but cannot be queried or indexed efficiently and accept
  invalid JSON.
- **A binary format such as Protobuf.** Compact and versioned, but unreadable in `psql`, the CLI and the
  dashboard without extra tooling, and it adds schema files to maintain.

## Consequences

- **Positive:** history is readable with plain SQL. The stored format changes only on purpose, in one class.
- **Negative:** JSON is larger than a binary format, and payload types must be JSON-friendly, such as records,
  strings, numbers and lists.
- **Follow-ups:** payload size limits (M5) and optional encryption, which wraps a payload in an envelope (M6).
