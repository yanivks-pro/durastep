# The demo

One command starts a small Durastep system; one more breaks it and checks that nothing was lost.

```bash
docker compose up -d --wait     # builds Durastep from source the first time
demo/chaos.sh                   # about 2 minutes
docker compose down             # when done
```

## What runs

| Service | What it does | Where |
|---|---|---|
| `postgres` | The only infrastructure Durastep needs | `127.0.0.1:15432` |
| `engine` ×3 | The example workflows: orders with a flaky payment gateway, trips that sometimes find no car and undo their bookings, expense claims that wait for a manager, and reports whose slow activity sends heartbeats | metrics on port 8081 inside the network |
| `load` | A client with no engine: starts about 5 workflows a second and approves or rejects expense claims | |
| `dashboard` | `durastep dashboard`, read-only | [http://127.0.0.1:8099](http://127.0.0.1:8099) |
| `prometheus` | Scrapes every engine and the load | [http://127.0.0.1:9090](http://127.0.0.1:9090) |
| `grafana` | The Durastep dashboard | [http://127.0.0.1:3000](http://127.0.0.1:3000) |

Every port listens on this machine only. The CLI works against the demo too: `durastep list --blocked`.

## What the chaos script does

`demo/chaos.sh` runs 20 rounds, 4 seconds apart; `--rounds` and `--gap` change that, and `--seed` repeats the
same sequence of kills and freezes on the same version of bash. Each round picks a random
engine and either kills it with `kill -9`, restarting it a few seconds later, or freezes it for 8 seconds. A
frozen engine is a zombie: its leases expire, another engine takes over its work, and when it wakes up, its
late result is rejected. Then the script stops the load, waits for every workflow started during the chaos to
finish, starts the load again and prints a report:

```text
Durastep chaos report
  workflows started                        936
  completed                                902
  failed as designed (no car left)         34
  cancelled                                0
  lost (unfinished or blocked)             0
  duplicate effects                        0
  kills (kill -9)                          15
  freezes                                  5
  zombie results rejected                  1
  repeats absorbed by idempotency keys     1
  duration                                 246s
```

- **Lost** counts workflows that are still unfinished or blocked once the load has stopped and the rest have
  drained. It must be 0.
- **Duplicate effects** counts effects that happened twice: each activity in the demo records its effect, such as
  a charge or a booking, under its idempotency key, and the same effect for the same workflow under two keys would
  mean a step ran twice as two different steps. It must be 0.
- **Repeats absorbed by idempotency keys** counts effects that were attempted again under the same key, because an
  engine died after doing the work but before saving that it was done. The key turned them away.
- **Zombie results rejected** counts late results that frozen engines tried to save after another engine had
  taken their work over. A zombie usually notices first, at its next heartbeat, and stops before repeating the
  effect; the fencing check rejects what is left.

The script exits with an error if anything was lost or done twice, which is how CI uses it.

## Recording

`demo/record.sh` regenerates every recording and screenshot in `docs/media`: it starts the demo, records
`demo/chaos.sh --rounds 10 --gap 3 --seed 6` and a CLI session with [asciinema](https://asciinema.org), turns them
into GIFs with [agg](https://github.com/asciinema/agg), and photographs the dashboard when Chrome is installed.
It needs `asciinema`, `agg` and `jq`; on a Mac, `brew install asciinema agg jq`.
