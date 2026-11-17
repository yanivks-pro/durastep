# ADR-0027: Pluggable payload encryption

- **Status:** Accepted
- **Date:** 2026-11-17
- **Requirements:** NFR-17, NFR-18

## Context

Workflow data is stored in Postgres for as long as the workflow and its retention period last: inputs, activity
arguments and results, signals and results ([ADR-0014](0014-jsonb-payloads-and-event-codec.md)). It often includes personal or
sensitive data, such as names, addresses or payment tokens, and database backups, replicas and support
access widen who can read it. Teams that must protect such data at rest need it encrypted before it leaves the
application, with keys they control and can rotate.

## Decision

Workflow data passes through an optional `PayloadCodec` on its way to and from the database; `AesGcmPayloadCodec`
is the reference implementation.

- **What is encoded:** every payload that passes through the engine's converter: workflow inputs and results,
  activity arguments and results, and signal payloads. Size limits apply to the data before encoding
  ([ADR-0024](0024-retention-and-size-limits.md)). IDs, names, statuses and error messages are not encoded, so the
  CLI, metrics and dashboard keep working; the security guide says not to put secrets in them.
- **The envelope:** encoded data is stored as JSON, `{"enc":"v1","key":"<key id>","data":"<base64>"}`, in the same
  JSONB columns, so the schema and the history format do not change.
- **AES-GCM:** each payload gets a fresh 96-bit IV and a 128-bit authentication tag, and the key ID is
  authenticated as associated data, so changed data or a relabelled key is rejected rather than misread.
- **Keys and rotation:** the application supplies the keys by ID and names one current. New data uses the current
  key; data is decrypted with the key its envelope names. Data stored before encryption was turned on is read as
  it is.
- **Everywhere data is read:** engines and clients take the codec (`payloadCodec(...)`,
  `DurastepClient.connect(dataSource, schema, codec)`); the Spring Boot starter picks up a `PayloadCodec` bean;
  the testkit replays encrypted histories with `withPayloadCodec(...)`. Without the codec, reading encrypted data
  fails with a message that says so. The CLI has no keys and shows `[encrypted]`.

## Alternatives considered

- **Postgres-side encryption** (`pgcrypto` or disk encryption). Protects the disk, but the database, its users and
  its backups of logical data still see plaintext, and keys live with the data.
- **Encrypting whole history events.** Would also hide activity names and step order, which operators need to fix
  workflows, without protecting anything more.
- **A built-in key store.** Key management is the application's job and usually belongs to a secret manager or a
  KMS; Durastep only needs the keys in memory.

## Consequences

- **Positive:** sensitive workflow data is unreadable in the database and its backups. A test runs a workflow
  carrying a card number and a PIN through inputs, activities, a signal and the result, then searches every stored
  JSON column: neither appears; with the encryption skipped on purpose, that test fails. Keys can be rotated while
  workflows run.
- **Negative:** encrypted data cannot be queried or indexed in SQL, and is about a third larger. Losing a key
  makes the data it encrypted unreadable, so keys must be kept as long as the data that uses them, which
  retention bounds. Error messages and statuses stay readable and must not carry secrets.
