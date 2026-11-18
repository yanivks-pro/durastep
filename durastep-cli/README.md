# Durastep CLI

Find, inspect and fix workflows from the command line. The CLI talks straight to the database, so it works
whether or not your application is running.

```bash
./mvnw -q install -Dmaven.test.skip=true
alias durastep='java -jar durastep-cli/target/durastep-cli-0.6.0.jar'
durastep --help
```

It connects to `jdbc:postgresql://localhost:15432/durastep`, the database from `docker compose up`, unless you
set `--url`, `--user` and `--password`, or `DURASTEP_DB_URL`, `DURASTEP_DB_USER` and `DURASTEP_DB_PASSWORD`.

## Look

```text
$ durastep list --limit 2
ID              TYPE           STATE      BLOCKED  STARTED              STATUS
trip-c5c255e5   TripWorkflow   FAILED              2026-11-16 23:01:36  failed, nothing booked
order-a9bb12d2  OrderWorkflow  COMPLETED           2026-11-16 23:01:32  done, tracking number TRK-A9BB12D2

$ durastep show order-a9bb12d2
order-a9bb12d2   OrderWorkflow   COMPLETED   started 2026-11-16 23:01:32   closed 2026-11-16 23:01:34
status:  done, tracking number TRK-A9BB12D2

  #1      +0.0s  started
  #2      +0.1s  scheduled Payments.charge
  #3      +2.2s  Payments.charge completed
  ...
  #10     +2.5s  completed
```

| Command | What it does |
|---|---|
| `list [--state S] [--type T] [--blocked] [--limit N]` | Workflows, newest first |
| `show <id>` | The workflow, the error that blocks it, and a readable timeline |
| `history <id>` | The raw history events |
| `stats` | Running and blocked workflows, waiting work and its age |
| `dashboard [--port P] [--bind A]` | Serves the read-only [dashboard](../durastep-dashboard/README.md) on `http://127.0.0.1:8099/` |

Every command except `dashboard` takes `--json` for scripts.

## Fix

| Command | What it does |
|---|---|
| `signal <id> <name> '<json>' [--request-id R]` | Sends a signal; the same request ID is accepted once |
| `cancel <id>` | Asks the workflow to stop and clean up |
| `terminate <id> --reason "…"` | Stops it at once, without cleanup, for example a blocked one |
| `retry-now <id> \| --all-blocked --reason "…"` | Retries blocked workflows now instead of after their backoff |
| `rerun <id> --reason "…"` | Starts a finished workflow again with the same input |

`cancel`, `terminate`, `rerun` and `retry-now --all-blocked` ask for confirmation; `--yes` skips the question.
Without a terminal they refuse unless `--yes` is given. Every action is recorded in the workflow's history
with who did it, `--as <name>` or your user name, and why.

Exit codes: `0` done, `1` refused or failed, `2` wrong usage.

## Docker

```bash
docker build -t durastep-cli durastep-cli
docker run --rm -e DURASTEP_DB_URL=jdbc:postgresql://host.docker.internal:15432/durastep durastep-cli list
```
