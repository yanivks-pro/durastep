# Getting started

From an empty project to a workflow that survives a crash, in about fifteen minutes. You need Java 25 and a
Postgres database; `docker compose up -d --wait postgres` in this repository gives you one on `localhost:15432`.

## 1. Add Durastep

Durastep is not on Maven Central yet. Build it once, then depend on the core:

```bash
git clone https://github.com/yanivks-pro/durastep.git
cd durastep && ./mvnw -q install -Dmaven.test.skip=true
```

```xml
<dependency>
  <groupId>io.github.yanivks-pro</groupId>
  <artifactId>durastep-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

With Spring Boot, use `durastep-spring-boot-starter` instead and skip to [step 5](#5-with-spring-boot).

## 2. Describe the work as activities

An activity is a step that talks to the outside world. Declare it as an interface, and implement it with ordinary
code:

```java
public interface Shop {
  String chargeCard(String orderId, long cents);
  void sendConfirmation(String orderId);
}

final class RealShop implements Shop {
  @Override
  public String chargeCard(String orderId, long cents) {
    return payments.charge(orderId, cents, ActivityContext.current().idempotencyKey());
  }

  @Override
  public void sendConfirmation(String orderId) {
    mail.send(orderId);
  }
}
```

An activity may run more than once, for example when an engine crashes after doing the work but before saving
that it did. Pass the idempotency key to the system you call, so it can ignore the repeat.

## 3. Write the process as a workflow

A workflow is plain Java that calls activities. It must be deterministic: the
[guide to writing workflows](writing-workflows.md) explains what that means.

```java
public final class OrderWorkflow implements Workflow<Order, String> {
  @Override
  public String run(WorkflowContext ctx, Order order) {
    Shop shop = ctx.activities(Shop.class);
    String charge = shop.chargeCard(order.id(), order.cents());
    shop.sendConfirmation(order.id());
    return charge;
  }
}
```

## 4. Start an engine and a workflow

```java
DurastepEngine engine = DurastepEngine.builder()
    .dataSource(dataSource)
    .workflow(OrderWorkflow.class, OrderWorkflow::new)
    .activities(Shop.class, new RealShop(payments, mail))
    .build();
engine.start();

WorkflowHandle<String> handle = engine.client().start(OrderWorkflow.class, "order-42", order);
String charge = handle.result(Duration.ofSeconds(30));
```

On start, the engine creates its tables in a `durastep` schema. The workflow ID is your business ID: starting
`order-42` a second time returns the same workflow instead of charging again. Run the same code on several
instances and they share the work.

Now stop the process in the middle of a workflow and start it again: the workflow carries on from the step it
was in, and the steps that finished are not repeated.

## 5. With Spring Boot

Add `durastep-spring-boot-starter`, and annotate instead of registering:

```java
@WorkflowDefinition
class OrderWorkflow implements Workflow<Order, String> { … }

@ActivityImplementation
class RealShop implements Shop { … }
```

The engine uses the application's `DataSource`, starts with it, stops during its graceful shutdown, and appears in
`/actuator/health`. Inject `DurastepClient` to start workflows. Settings are under `durastep.*`; the
[starter's guide](../../durastep-spring-boot-starter/README.md) lists them.

## 6. Look at it

```bash
durastep list
durastep show order-42
durastep dashboard
```

The [CLI](../../durastep-cli/README.md) shows each workflow's timeline and the error of any blocked one, and the
dashboard shows the same in a browser.

## Next

- [Writing workflows](writing-workflows.md): retries, timers, signals, cancellation, sagas, parallel steps and
  changing code safely.
- [Operations](operations.md): running Durastep in production, watching it and fixing workflows.
- [Security](security.md): what Durastep stores, database privileges and encryption.
- [Examples](../../durastep-examples/README.md): order processing, trip booking and expense approval.
