package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CancellationIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Shop {
    String charge(String orderId);

    void ship(String orderId);

    void refund(String orderId);
  }

  static final class GatedShop implements Shop {
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile CountDownLatch shipping = new CountDownLatch(0);

    @Override
    public String charge(String orderId) {
      calls.add("charge " + orderId);
      return "ch_" + orderId;
    }

    @Override
    public void ship(String orderId) {
      calls.add("ship started " + orderId);
      try {
        if (!shipping.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      calls.add("ship finished " + ActivityContext.current().idempotencyKey());
    }

    @Override
    public void refund(String orderId) {
      calls.add("refund " + orderId);
    }
  }

  static final class OrderWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      Shop shop = ctx.activities(Shop.class);
      String chargeId = shop.charge(orderId);
      try {
        shop.ship(orderId);
        return chargeId;
      } catch (CancelledException e) {
        ctx.setStatus("refunding after cancellation");
        shop.refund(orderId);
        throw e;
      }
    }
  }

  static final class SleepingWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String id) {
      ctx.sleep(Duration.ofDays(30));
      return "woke up";
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final GatedShop shop = new GatedShop();
  private DurastepEngine engine;

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
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .workflow(SleepingWorkflow.class, SleepingWorkflow::new)
            .activities(Shop.class, shop)
            .build();
    engine.start();
  }

  @AfterEach
  void stopEngine() {
    shop.shipping.countDown();
    engine.close();
  }

  @Test
  void anOrderCancelledWhileShippingIsRefundedAndItsShippingResultIsIgnored() throws Exception {
    shop.shipping = new CountDownLatch(1);
    WorkflowHandle<String> order = engine.client().start(OrderWorkflow.class, "order-1", "order-1");
    await().atMost(WAIT).until(() -> shop.calls.contains("ship started order-1"));

    assertThat(engine.client().cancel("order-1")).isTrue();

    assertThatThrownBy(() -> order.result(WAIT)).isInstanceOf(WorkflowCancelledException.class);
    assertThat(order.describe().state()).isEqualTo(WorkflowState.CANCELLED);
    assertThat(order.describe().status()).isEqualTo("refunding after cancellation");
    assertThat(shop.calls)
        .containsExactly("charge order-1", "ship started order-1", "refund order-1");

    shop.shipping.countDown();
    await().atMost(WAIT).until(() -> shop.calls.contains("ship finished order-1/activity-4"));
    Thread.sleep(Duration.ofMillis(500));
    assertThat(
            count(
                "durastep.history_events WHERE workflow_id = 'order-1'"
                    + " AND event_type = 'ACTIVITY_COMPLETED' AND payload->>'scheduledSeq' = '4'"))
        .isZero();
    assertThat(engine.client().cancel("order-1")).isFalse();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aWorkflowSleepingForThirtyDaysIsCancelledAtOnce() throws Exception {
    WorkflowHandle<String> sleeper =
        engine.client().start(SleepingWorkflow.class, "sleeper", "sleeper");
    await().atMost(WAIT).until(() -> count("durastep.timers") == 1);

    long cancelling = System.nanoTime();
    engine.client().cancel("sleeper");

    assertThatThrownBy(() -> sleeper.result(WAIT)).isInstanceOf(WorkflowCancelledException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - cancelling)).isLessThan(Duration.ofSeconds(3));
    assertThat(count("durastep.timers")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void cancellingAnUnknownWorkflowIsAnError() {
    assertThatThrownBy(() -> engine.client().cancel("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int count(String from) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT count(*) FROM " + from)) {
      row.next();
      return row.getInt(1);
    }
  }
}
