# Security considerations

Durastep keeps everything it knows in your Postgres database: every workflow, its full history, and the data
that passes through it. This guide covers what that data is, who can read it, and how to keep it safe.

## What Durastep stores

| Data | Where | Readable by tools |
|---|---|---|
| Workflow inputs and results, activity arguments and results, signal payloads | `workflows`, `history_events`, `inbox` | Encrypted with a [payload codec](#encrypt-workflow-data-at-rest), otherwise plain JSON |
| Workflow IDs and types, activity and signal names, step order, timestamps | all tables | Always, so the CLI and dashboard can work |
| Statuses set with `ctx.setStatus(...)` | `workflows` | Always |
| Error types and messages of failed activities and blocked workflows | `history_events`, `workflows`, `tasks` | Always |
| Who ran an operator command and why | `history_events` | Always |
| The trace context of the code that started a workflow, with [tracing](../durastep-opentelemetry/README.md) on | `workflows` | Always; trace and span IDs only |

Finished workflows are deleted after the retention period, 30 days by default.

## Keep secrets out of workflow data

Pass references, not secrets. A workflow should carry a payment token, not a card number; a user ID, not a
password; the key of a document in object storage, not the document itself.

```java
String token = payments.tokenize(order.cardNumber());   // in the checkout code, before the workflow starts
client.start(OrderWorkflow.class, order.id(), new OrderInput(order.id(), token));
```

Statuses, error messages and IDs are never encrypted. Do not put personal data or secrets in them: throw
`new IllegalStateException("card declined")`, not an exception that repeats the card number.

## Use a dedicated database user

Run engines as a user that can read and write Durastep's tables and nothing else, and let a separate owner create
and change those tables. Durastep needs no other privileges: notifications and advisory locks work for any user.

```sql
CREATE ROLE durastep_owner LOGIN PASSWORD 'change-me-owner';
CREATE ROLE durastep_app LOGIN PASSWORD 'change-me-app';
CREATE SCHEMA durastep AUTHORIZATION durastep_owner;
GRANT USAGE ON SCHEMA durastep TO durastep_app;
ALTER DEFAULT PRIVILEGES FOR ROLE durastep_owner IN SCHEMA durastep
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO durastep_app;
```

Apply the migrations as `durastep_owner`, for example from a deploy job that starts one engine with the owner's
credentials, and run every application engine as `durastep_app` with migrations turned off:

```java
DurastepEngine.builder().dataSource(appDataSource).applyMigrations(false)…
```

```yaml
durastep:
  apply-migrations: false
```

`durastep_app` cannot create, change or drop tables, and cannot read other schemas. The CLI needs the same
privileges as the application for commands that change workflows; for `list`, `show`, `history` and `stats`, a
user with only `SELECT` on the schema is enough. A test runs exactly the SQL above and checks all of this.

## Encrypt the connection

Use TLS between your application and Postgres, and verify the server:

```text
jdbc:postgresql://db.example.com:5432/app?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem
```

## Encrypt workflow data at rest

With a payload codec, inputs, arguments, results and signals are encrypted before they leave the application,
so they are unreadable in the database and its backups ([ADR-0027](adr/0027-pluggable-payload-encryption.md)):

```java
PayloadCodec codec = AesGcmPayloadCodec.withKeys(Map.of("2026-11", currentKey), "2026-11");
DurastepEngine.builder().dataSource(dataSource).payloadCodec(codec)…
```

In Spring Boot, declare the codec as a bean and the starter uses it.

- **Keep keys in a secret manager**, not in configuration files or the database. `AesGcmPayloadCodec.newKey()`
  creates one.
- **Rotate** by adding a new key and making it current. Old data keeps naming the key it was encrypted with, so
  keep old keys until no stored data uses them; with retention, that is at most the retention period after the
  last workflow that used them finished.
- **Give every client and engine the same keys.** Without them, reading encrypted data fails with a message that
  says so, and the CLI shows `[encrypted]`.

## Logs

Durastep never logs workflow data: its log lines carry IDs, activity names, attempt numbers, durations and the
types of errors. A test runs a workflow whose input, arguments, results and signals hold secrets, with retries
and a blocked phase, at the most detailed log level, and finds none of them in the logs. There is no switch that
logs workflow data.

Two things can still reach the logs. Exceptions are logged with their messages, so the rule above about error
messages applies. And when stored data can no longer be converted to the types the code expects, for example
after a field changed type, the JSON library's error can quote the value it could not convert.

## Operators

Operator commands, from the CLI or the client, record who acted and why in the workflow's history. The CLI acts
as whatever database user it connects as, so give operators their own database users and use `--as` to name the
person.

## Data minimisation

Keep the retention period as short as your audits allow (`retention` on the builder, `durastep.retention` in
Spring Boot). Deleting a finished workflow deletes its whole history.
