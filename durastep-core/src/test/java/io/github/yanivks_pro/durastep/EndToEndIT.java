package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class EndToEndIT {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private static final ActivityOptions QUICK_RETRIES =
      ActivityOptions.builder()
          .retryPolicy(
              RetryPolicy.builder()
                  .maxAttempts(2)
                  .initialBackoff(Duration.ofMillis(10))
                  .maxBackoff(Duration.ofMillis(20))
                  .build())
          .build();

  record Order(String id, long cents) {}

  record Receipt(String chargeId, Instant confirmedAt) {}

  interface OrderActivities {
    String chargeCard(String orderId, long cents);

    void reserveStock(String orderId);

    void sendEmail(String orderId);
  }

  static final class OrderWorkflow implements Workflow<Order, Receipt> {
    @Override
    public Receipt run(WorkflowContext ctx, Order order) {
      OrderActivities activities = ctx.activities(OrderActivities.class, QUICK_RETRIES);
      String chargeId = activities.chargeCard(order.id(), order.cents());
      activities.reserveStock(order.id());
      activities.sendEmail(order.id());
      return new Receipt(chargeId, ctx.now());
    }
  }

  static final class OtherWorkflow implements Workflow<Order, String> {
    @Override
    public String run(WorkflowContext ctx, Order order) {
      return order.id();
    }
  }

  static final class ShopActivities implements OrderActivities {
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean outOfStock;
    volatile CountDownLatch emailGate = new CountDownLatch(0);

    @Override
    public String chargeCard(String orderId, long cents) {
      record("chargeCard");
      return "ch_" + orderId;
    }

    @Override
    public void reserveStock(String orderId) {
      record("reserveStock");
      if (outOfStock) {
        throw new IllegalStateException("out of stock");
      }
    }

    @Override
    public void sendEmail(String orderId) {
      try {
        if (!emailGate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the email gate was never opened");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      record("sendEmail");
    }

    private void record(String activity) {
      calls.add(activity + " " + ActivityContext.current().idempotencyKey());
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final ShopActivities shop = new ShopActivities();
  private DurastepEngine engine;
  private DurastepClient client;

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @BeforeEach
  void startEngine() throws SQLException {
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .activities(OrderActivities.class, shop)
            .build();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
    engine.start();
    client = engine.client();
  }

  @AfterEach
  void stopEngine() {
    shop.emailGate.countDown();
    engine.close();
  }

  @Test
  void anOrderRunsEveryActivityOnceAndReturnsItsReceipt() throws Exception {
    WorkflowHandle<Receipt> order =
        client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200));

    Receipt receipt = order.result(WAIT);

    assertThat(receipt.chargeId()).isEqualTo("ch_order-42");
    assertThat(receipt.confirmedAt()).isNotNull();
    assertThat(shop.calls)
        .containsExactly(
            "chargeCard order-42/activity-2",
            "reserveStock order-42/activity-4",
            "sendEmail order-42/activity-6");
  }

  @Test
  void startingTheSameOrderTwiceRunsItOnce() throws Exception {
    WorkflowHandle<Receipt> first =
        client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200));
    WorkflowHandle<Receipt> second =
        client.start(OrderWorkflow.class, "order-42", new Order("order-42", 9999));

    assertThat(second.result(WAIT)).isEqualTo(first.result(WAIT));
    assertThat(shop.calls).hasSize(3);
  }

  @Test
  void aFailingActivityFailsTheWorkflowWithItsError() {
    shop.outOfStock = true;
    WorkflowHandle<Receipt> order =
        client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200));

    assertThatThrownBy(() -> order.result(WAIT))
        .isInstanceOfSatisfying(
            WorkflowFailedException.class,
            failed -> {
              assertThat(failed.workflowId()).isEqualTo("order-42");
              assertThat(failed.failureType()).isEqualTo(ActivityFailedException.class.getName());
              assertThat(failed.getMessage())
                  .contains("reserveStock failed after 2 attempts")
                  .contains("out of stock");
            });
    assertThat(shop.calls)
        .containsExactly(
            "chargeCard order-42/activity-2",
            "reserveStock order-42/activity-4",
            "reserveStock order-42/activity-4");
  }

  @Test
  void aTimeoutEndsOnlyTheWaitAndTheWorkflowCarriesOn() throws Exception {
    shop.emailGate = new CountDownLatch(1);
    WorkflowHandle<Receipt> order =
        client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200));

    assertThatThrownBy(() -> order.result(Duration.ofMillis(500)))
        .isInstanceOf(TimeoutException.class);

    shop.emailGate.countDown();
    assertThat(order.result(WAIT).chargeId()).isEqualTo("ch_order-42");
  }

  @Test
  void aHandleCanBeObtainedLaterByIdAndClass() throws Exception {
    client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200)).result(WAIT);

    WorkflowHandle<Receipt> later = client.handle("order-42", OrderWorkflow.class);

    assertThat(later.result(WAIT).chargeId()).isEqualTo("ch_order-42");
  }

  @Test
  void reusingAnIdForAnotherWorkflowTypeIsRejected() {
    client.start(OrderWorkflow.class, "order-42", new Order("order-42", 4200));

    assertThatThrownBy(
            () -> client.start(OtherWorkflow.class, "order-42", new Order("order-42", 4200)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists as OrderWorkflow");
    assertThatThrownBy(() -> client.handle("order-99", OrderWorkflow.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no workflow order-99");
  }
}
