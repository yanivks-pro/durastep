# Durastep Spring Boot starter

Runs Durastep inside a Spring Boot 4 application, using the application's `DataSource` and lifecycle.

```xml
<dependency>
  <groupId>io.github.yanivks-pro</groupId>
  <artifactId>durastep-spring-boot-starter</artifactId>
  <version>0.6.0</version>
</dependency>
```

```java
@WorkflowDefinition
class OrderWorkflow implements Workflow<Order, Receipt> {
  private final PriceList prices;

  OrderWorkflow(PriceList prices) {
    this.prices = prices;
  }

  @Override
  public Receipt run(WorkflowContext ctx, Order order) {
    String charge = ctx.activities(Payments.class).charge(order.id(), prices.priceOf(order));
    return new Receipt(charge);
  }
}

@ActivityImplementation
class ShopActivities implements Payments, Warehouse { … }

@Service
class Checkout {
  private final DurastepClient durastep;

  Checkout(DurastepClient durastep) {
    this.durastep = durastep;
  }

  void placeOrder(Order order) {
    durastep.start(OrderWorkflow.class, order.id(), order);
  }
}
```

`@WorkflowDefinition` makes a workflow a prototype bean, so every run gets a fresh instance with its
dependencies injected. `@ActivityImplementation` registers a bean for every interface it implements, or for the
interfaces it names.

With [`durastep-opentelemetry`](../durastep-opentelemetry/README.md) on the classpath and an `OpenTelemetry` bean,
workflows are traced: the engine and the client use it, or any `WorkflowTracing` bean of your own.

## Settings

```yaml
durastep:
  schema: durastep
  workflow-concurrency: 10
  activity-concurrency: 100
  activity-queues: [default]
  lease-duration: 30s
  shutdown-grace: 20s
  max-payload-size: 256KB
  retention: 30d            # or "off"
  history:
    warn-at: 10000
    limit: 50000
  worker:
    enabled: true           # false: a client only, without workers
```

The engine starts when the application is ready and stops during Spring's graceful shutdown. With Spring Boot
Actuator, `/actuator/health` includes a `durastep` component: `UP` while the database answers, with the number of
running and blocked workflows and the age of the oldest waiting work. With `durastep-micrometer` and a
`MeterRegistry`, the engine's metrics are recorded automatically.
