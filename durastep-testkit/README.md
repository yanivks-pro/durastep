# Durastep testkit

`WorkflowReplayer` checks, in a normal unit test, that new workflow code still replays the histories of
workflows that are running or have finished. A change that would block running workflows after a deploy fails
the build instead.

## 1. Save histories

Use the CLI to save a few real histories, one file per workflow, named after its ID:

```bash
durastep history order-42 --json > src/test/resources/histories/order-42.json
```

Pick histories that cover the paths your workflow takes, including ones that are still running.

## 2. Replay them

```xml
<dependency>
  <groupId>io.github.yanivks-pro</groupId>
  <artifactId>durastep-testkit</artifactId>
  <version>0.6.0</version>
  <scope>test</scope>
</dependency>
```

```java
@Test
void theNewCodeStillReplaysSavedOrders() {
  WorkflowReplayer.of(OrderWorkflow.class, OrderWorkflow::new)
      .replay(Path.of("src/test/resources/histories"));
}
```

A failure names each history and the first difference:

```text
2 of 2 histories no longer replay with OrderWorkflow:
- order-1.json: workflow order-1 called Shop.sms at step 2, but its history has Shop.email there
- order-2.json: workflow order-2 called Shop.sms at step 2, but its history has Shop.email there
```

Put the change behind `ctx.version(...)` and the same histories replay again. A workflow that finished must also
finish the same way, with an equal result, a failure or a cancellation.

Like the engine, the replayer compares which activities, timers and signal waits are called and in what order,
not the arguments passed to them.
