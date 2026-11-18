# Durastep OpenTelemetry

One trace per workflow: the request that starts a workflow, every attempt of its activities and the calls they
make, its timers, its signals and its end, whichever engine does the work and however much later.

```text
POST /orders
├─ activity Payments.charge   attempt 1   error: java.lang.IllegalStateException
├─ activity Payments.charge   attempt 2
│   └─ POST https://psp.example/charge
├─ timer sleep PT2H
├─ signal approve
└─ workflow OrderWorkflow     completed
```

## Use it

```xml
<dependency>
  <groupId>io.github.yanivks-pro</groupId>
  <artifactId>durastep-opentelemetry</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
WorkflowTracing tracing = DurastepTracing.of(openTelemetry);

DurastepEngine engine = DurastepEngine.builder().dataSource(dataSource).tracing(tracing)…build();
DurastepClient client = DurastepClient.connect(dataSource).withTracing(tracing);   // clients without an engine
```

The module needs only `opentelemetry-api`; the application brings the SDK and an exporter. With the Spring Boot
starter, an `OpenTelemetry` bean is enough.

## What the spans carry

| Span | When | Attributes |
|---|---|---|
| `activity <name>` | Each attempt; current inside the activity | `durastep.workflow.id`, `durastep.workflow.type`, `durastep.activity.attempt`, `durastep.activity.outcome`, `error.type` when it failed |
| `timer <what>` | From start to fire, such as `sleep PT2H` | workflow ID and type |
| `signal <name>` | When the signal arrived | workflow ID and type |
| `workflow <type>` | From start to finish | workflow ID and type, `durastep.workflow.state` |
| `start <type>` | Only when no span was current at start, as the root | workflow ID and type |

Spans never carry workflow data: no inputs, arguments or results, and only the type of an error, not its
message.

Why it is built this way: [ADR-0030](../docs/adr/0030-one-trace-per-workflow.md).
