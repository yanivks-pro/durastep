# Durastep examples

Three small applications that show what Durastep does, each with scripted scenarios. The outside systems
(payments, airlines, approvers) are in-memory fakes that print what happens and can be told to fail. The
workflows only see interfaces, so they do not know the difference.

The examples use only the public `api` package; a test makes sure of it.

## Run them

You need Docker and Java 25.

```bash
docker compose up -d --wait
./mvnw -q install -Dmaven.test.skip=true
./mvnw -q -pl durastep-examples exec:java -Dexample=trip -Dscenario=no-cars
```

Leave out `-Dexample` to list every example and scenario. The database is reachable on `localhost:15432`;
use `-Ddb.url=...`, `-Ddb.user=...` and `-Ddb.password=...` to point the examples elsewhere.

## Order processing: retries and idempotency

`order/OrderWorkflow`: charge the card, reserve the item, ship, send a confirmation.

| Scenario | What happens |
|---|---|
| `happy` | Every step succeeds once. |
| `flaky-payments` | The payment gateway times out, then charges the card but drops the connection before answering. The engine retries; the third call has the same idempotency key, so the gateway returns the existing charge. |

```text
  0.48s  payments    attempt 1: gateway timed out, nothing charged
  1.52s  payments    attempt 2: charged 4999 cents, then the connection dropped
  2.58s  payments    already charged with key order-15a4fa4a/activity-2, returning ch_1
  ...
  2.95s  payments    charge was called 3 times, the card was charged once
```

## Trip booking: parallel branches, a saga and cancellation

`trip/TripWorkflow`: book the flight and the hotel at the same time with `ctx.async`, then a car. Each booking
registers its undo step with the saga before it starts, keyed by the trip, so a booking whose outcome is unknown
is undone too.

| Scenario | What happens |
|---|---|
| `happy` | Flight and hotel are booked in parallel, then the car: about two seconds instead of three. |
| `no-cars` | The car rental is sold out. The saga cancels the hotel, then the flight, and the trip fails. |
| `customer-cancels` | The traveller cancels while the car is being booked. The workflow undoes everything and ends cancelled; the car booking that lands afterwards is released. |

```text
  2.61s  car rental  no cars left in Lisbon
  2.70s  status      undoing the bookings
  2.70s  car rental  nothing to cancel for the car (yet)
  2.77s  hotel       cancelled the hotel
  2.85s  airline     cancelled the flight
  2.94s  status      failed, nothing booked
```

## Expense approval: signals, a published status, timeouts and escalation

`expense/ExpenseWorkflow`: wait for the manager's decision, sent as a signal. Without an answer in time, ask the
director; without an answer from either, reject the claim. Each stage publishes a status, which the example
prints as it changes. In the story the waits are days; in the demo they are three seconds.

| Scenario | What happens |
|---|---|
| `approve` | The manager approves, and the claim is paid. |
| `reject` | The manager rejects, and the employee is told why. |
| `escalate` | The manager does not answer; the director approves. |
| `nobody-answers` | Neither answers, and the claim is rejected. |
| `withdraw` | The employee withdraws the claim while it waits; the workflow tells them and ends cancelled. |

```text
  0.50s  inbox       asked the manager to approve expense-b38a0361
  4.13s  inbox       no answer from the manager, asked the director
  4.15s  status      escalated to the director
  5.16s  Linus       approves: approved
  5.28s  finance     paid 12000 cents to Ada
```

## Watch it in Grafana

The load generator runs a steady mix of all three workflows, with flaky payments, sold-out cars and slow
approvers, and serves its metrics for Prometheus. Start Postgres, Prometheus and Grafana, then the load:

```bash
docker compose --profile observability up -d --wait
./mvnw -q install -Dmaven.test.skip=true
./mvnw -q -pl durastep-examples exec:java -Dexample=load
```

Open [http://localhost:3000](http://localhost:3000): the **Durastep** dashboard shows workflows started and
finished, running and blocked workflows, waiting work and its age, activity attempts by outcome, the 95th
percentile of activity and decision times, timer lateness, lease takeovers and rejected late results.
Prometheus is on [http://localhost:9090](http://localhost:9090), and the raw metrics on
[http://localhost:8081/metrics](http://localhost:8081/metrics). Stop the load with Ctrl-C, or run it for a while
with `-Dminutes=5`.

## Tests

Each scenario runs end to end against a real Postgres in `*ExampleIT`, as part of `./mvnw verify`. Another test checks that every
metric the Grafana dashboard queries is served by the load generator.
