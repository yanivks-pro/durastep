package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.MDC;
import org.testcontainers.postgresql.PostgreSQLContainer;

class EngineListenerIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Shop {
    String charge(String order);

    String whereAmI();

    String slowOnce();
  }

  static final class FlakyShop implements Shop {
    final AtomicInteger charges = new AtomicInteger();
    final AtomicInteger slowCalls = new AtomicInteger();

    @Override
    public String charge(String order) {
      if (charges.incrementAndGet() == 1) {
        throw new IllegalStateException("gateway timed out");
      }
      return "charged " + order;
    }

    @Override
    public String whereAmI() {
      return MDC.get("workflowId") + " " + MDC.get("activity") + " " + MDC.get("attempt");
    }

    @Override
    public String slowOnce() {
      if (slowCalls.incrementAndGet() == 1) {
        try {
          Thread.sleep(Duration.ofSeconds(3));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return "done";
    }
  }

  static final class Order implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      Shop shop =
          ctx.activities(
              Shop.class,
              ActivityOptions.builder()
                  .retryPolicy(RetryPolicy.builder().initialBackoff(Duration.ofMillis(50)).build())
                  .build());
      String charged = shop.charge(order);
      ctx.sleep(Duration.ofMillis(200));
      return charged + ", logged as " + shop.whereAmI();
    }
  }

  static final class Refused implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      throw new WorkflowFailedException("refused " + order);
    }
  }

  static final class Slow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return ctx.activities(
              Shop.class,
              ActivityOptions.builder().heartbeatTimeout(Duration.ofMillis(500)).build())
          .slowOnce();
    }
  }

  static final class Recorder implements EngineListener {
    final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void workflowStarted(String type) {
      events.add("started " + type);
    }

    @Override
    public void workflowFinished(String type, WorkflowState state) {
      events.add("finished " + type + " " + state);
    }

    @Override
    public void decisionMade(String type, Duration took) {
      events.add("decision " + type);
    }

    @Override
    public void activityFinished(String activity, ActivityOutcome outcome, Duration took) {
      events.add("activity " + activity + " " + outcome);
    }

    @Override
    public void timerFired(Duration late) {
      events.add("timer late " + !late.isNegative());
    }

    @Override
    public void leaseExpired(WorkKind kind) {
      events.add("lease expired " + kind);
    }

    @Override
    public void staleResultRejected(WorkKind kind) {
      events.add("stale " + kind);
    }
  }

  static final class Broken implements EngineListener {
    @Override
    public void workflowStarted(String type) {
      throw new IllegalStateException("listener bug");
    }

    @Override
    public void decisionMade(String type, Duration took) {
      throw new IllegalStateException("listener bug");
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final FlakyShop shop = new FlakyShop();

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try (DurastepEngine schemaOnly = DurastepEngine.builder().dataSource(dataSource).build()) {
      schemaOnly.start();
    }
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @BeforeEach
  void emptyTables() throws SQLException {
    execute("TRUNCATE durastep.workflows CASCADE");
  }

  @Test
  void theListenerHearsAboutWorkflowsDecisionsActivitiesAndTimers() throws Exception {
    Recorder recorder = new Recorder();
    try (DurastepEngine engine = engine(recorder)) {
      String result = engine.client().start(Order.class, "order-1", "order-1").result(WAIT);
      engine.client().start(Refused.class, "refused-1", "x");
      await().atMost(WAIT).until(() -> recorder.events.contains("finished Refused FAILED"));

      assertThat(result).isEqualTo("charged order-1, logged as order-1 Shop.whereAmI 1");
    }

    assertThat(recorder.events)
        .contains(
            "started Order",
            "activity Shop.charge RETRIED",
            "activity Shop.charge SUCCEEDED",
            "timer late true",
            "activity Shop.whereAmI SUCCEEDED",
            "decision Order",
            "finished Order COMPLETED",
            "started Refused",
            "finished Refused FAILED")
        .doesNotContain("lease expired DECISION", "lease expired ACTIVITY");
    assertThat(recorder.events.indexOf("activity Shop.charge RETRIED"))
        .isLessThan(recorder.events.indexOf("activity Shop.charge SUCCEEDED"));
  }

  @Test
  void engineLogLinesCarryTheWorkflowTheActivityAndTheAttempt() throws Exception {
    ByteArrayOutputStream logs = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(logs, true, StandardCharsets.UTF_8));
    try (DurastepEngine engine = engine(EngineListener.NONE)) {
      engine.client().start(Order.class, "order-6", "order-6").result(WAIT);
    } finally {
      System.setErr(originalErr);
    }

    assertThat(logs.toString(StandardCharsets.UTF_8).lines())
        .filteredOn(line -> line.contains("failed with java.lang.IllegalStateException; retrying"))
        .singleElement()
        .asString()
        .contains("workflowId=order-6 activity=Shop.charge attempt=1");
  }

  @Test
  void aTakenOverActivityIsReportedAndItsLateResultIsRejected() throws Exception {
    Recorder recorder = new Recorder();
    try (DurastepEngine engine = engine(recorder)) {
      assertThat(engine.client().start(Slow.class, "slow-1", "x").result(WAIT)).isEqualTo("done");
      await()
          .atMost(WAIT)
          .until(
              () ->
                  recorder.events.contains("lease expired ACTIVITY")
                      && recorder.events.contains("stale ACTIVITY"));
    }
    assertThat(shop.slowCalls).hasValue(2);
  }

  @Test
  void aFailingListenerNeverBreaksAWorkflow() throws Exception {
    try (DurastepEngine engine = engine(new Broken())) {
      assertThat(engine.client().start(Order.class, "order-2", "order-2").result(WAIT))
          .startsWith("charged order-2");
    }
  }

  @Test
  void statsCountRunningAndBlockedWorkflowsAndWaitingWork() throws Exception {
    DurastepClient client = DurastepClient.connect(dataSource);
    client.start(Order.class, "order-3", "a");
    client.start(Order.class, "order-4", "b");
    client.start(Order.class, "order-5", "c");
    execute("UPDATE durastep.workflows SET blocked = true WHERE id = 'order-5'");
    Thread.sleep(Duration.ofMillis(200));

    EngineStats stats = client.stats();

    assertThat(stats.running()).isEqualTo(3);
    assertThat(stats.blocked()).isEqualTo(1);
    assertThat(stats.readyDecisions()).isEqualTo(3);
    assertThat(stats.readyActivities()).isEmpty();
    assertThat(stats.oldestReadyWork()).isGreaterThanOrEqualTo(Duration.ofMillis(150));
  }

  private DurastepEngine engine(EngineListener listener) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .listener(listener)
            .workflow(Order.class, Order::new)
            .workflow(Refused.class, Refused::new)
            .workflow(Slow.class, Slow::new)
            .activities(Shop.class, shop)
            .build();
    engine.start();
    return engine;
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
