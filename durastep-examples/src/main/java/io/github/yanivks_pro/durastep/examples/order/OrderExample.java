package io.github.yanivks_pro.durastep.examples.order;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.StatusWatcher;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

public final class OrderExample {

  public enum Scenario {
    HAPPY,
    FLAKY_PAYMENTS
  }

  public record Result(WorkflowState state, String trackingNumber, int chargeCalls, int charges) {}

  private OrderExample() {}

  public static Result run(DataSource dataSource, Scenario scenario, Narrator narrator)
      throws Exception {
    FakePayments payments = new FakePayments(scenario == Scenario.FLAKY_PAYMENTS, narrator);
    try (DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .activities(Payments.class, payments)
            .activities(Warehouse.class, new FakeWarehouse(narrator))
            .activities(Mail.class, new FakeMail(narrator))
            .build()) {
      engine.start();
      Order order =
          new Order(
              "order-" + UUID.randomUUID().toString().substring(0, 8), "Ada", "keyboard", 4_999);
      narrator.say(
          "shop", order.customer() + " orders a " + order.item() + " (" + order.id() + ")");
      WorkflowHandle<String> workflow =
          engine.client().start(OrderWorkflow.class, order.id(), order);
      String trackingNumber;
      StatusWatcher watcher = StatusWatcher.start(workflow, narrator);
      try {
        trackingNumber = workflow.result(Duration.ofSeconds(30));
      } finally {
        watcher.stop();
      }
      narrator.say(
          "payments",
          "charge was called "
              + times(payments.calls.get())
              + ", the card was charged "
              + times(payments.charges.size()));
      return new Result(
          workflow.describe().state(),
          trackingNumber,
          payments.calls.get(),
          payments.charges.size());
    }
  }

  private static String times(int count) {
    return count == 1 ? "once" : count + " times";
  }

  static final class PaymentGatewayException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PaymentGatewayException(String message) {
      super(message);
    }
  }

  static final class FakePayments implements Payments {
    private final boolean flaky;
    private final Narrator narrator;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, String> charges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    FakePayments(boolean flaky, Narrator narrator) {
      this.flaky = flaky;
      this.narrator = narrator;
    }

    @Override
    public String charge(String orderId, long cents) {
      calls.incrementAndGet();
      String key = ActivityContext.current().idempotencyKey();
      int attempt = attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      String existing = charges.get(key);
      if (existing != null) {
        narrator.say("payments", "already charged with key " + key + ", returning " + existing);
        return existing;
      }
      if (flaky && attempt == 1) {
        narrator.say("payments", "attempt 1: gateway timed out, nothing charged");
        throw new PaymentGatewayException("gateway timed out");
      }
      String chargeId = "ch_" + (charges.size() + 1);
      charges.put(key, chargeId);
      if (flaky && attempt == 2) {
        narrator.say(
            "payments", "attempt 2: charged " + cents + " cents, then the connection dropped");
        throw new PaymentGatewayException("connection dropped after charging");
      }
      narrator.say("payments", "charged " + cents + " cents for " + orderId + ": " + chargeId);
      return chargeId;
    }
  }

  static final class FakeWarehouse implements Warehouse {
    private final Narrator narrator;

    FakeWarehouse(Narrator narrator) {
      this.narrator = narrator;
    }

    @Override
    public void reserve(String orderId, String item) {
      narrator.say("warehouse", "reserved a " + item + " for " + orderId);
    }

    @Override
    public String ship(String orderId, String customer) {
      String trackingNumber =
          "TRK-" + orderId.substring(orderId.indexOf('-') + 1).toUpperCase(Locale.ROOT);
      narrator.say("warehouse", "shipped " + orderId + " to " + customer + ": " + trackingNumber);
      return trackingNumber;
    }
  }

  static final class FakeMail implements Mail {
    private final Narrator narrator;

    FakeMail(Narrator narrator) {
      this.narrator = narrator;
    }

    @Override
    public void sendConfirmation(String customer, String orderId, String trackingNumber) {
      narrator.say("mail", "told " + customer + " that " + orderId + " is on its way");
    }
  }
}
