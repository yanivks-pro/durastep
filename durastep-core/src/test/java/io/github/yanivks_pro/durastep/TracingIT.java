package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.ActivityTrace;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.TraceTarget;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.api.WorkflowTracing;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class TracingIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final ThreadLocal<String> CURRENT_TRACE = new ThreadLocal<>();

  interface Shop {
    String charge(String order);
  }

  static final class FlakyShop implements Shop {
    final AtomicInteger charges = new AtomicInteger();

    @Override
    public String charge(String order) {
      if (charges.incrementAndGet() == 1) {
        throw new IllegalStateException("gateway timed out for card 4111");
      }
      return "charged in " + CURRENT_TRACE.get();
    }
  }

  static final class Order implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      String charged =
          ctx.activities(
                  Shop.class,
                  ActivityOptions.builder()
                      .retryPolicy(
                          RetryPolicy.builder().initialBackoff(Duration.ofMillis(50)).build())
                      .build())
              .charge(order);
      ctx.sleep(Duration.ofMillis(100));
      String approval =
          ctx.awaitSignal("approve", String.class, Duration.ofMinutes(1)).orElse("never");
      return charged + ", " + approval;
    }
  }

  static final class Recorder implements WorkflowTracing {
    final String name;
    final List<String> calls = new CopyOnWriteArrayList<>();

    Recorder(String name) {
      this.name = name;
    }

    @Override
    public Map<String, String> capture(String workflowId, String workflowType) {
      calls.add("capture " + workflowId + " " + workflowType);
      return Map.of("traceparent", "from-" + name);
    }

    @Override
    public ActivityTrace activityStarted(TraceTarget target) {
      calls.add("activity " + describe(target));
      return new ActivityTrace() {
        @Override
        public <T> Callable<T> wrap(Callable<T> attempt) {
          return () -> {
            CURRENT_TRACE.set(target.context().get("traceparent") + "#" + target.attempt());
            try {
              return attempt.call();
            } finally {
              CURRENT_TRACE.remove();
            }
          };
        }

        @Override
        public void end(ActivityOutcome outcome, String failureType) {
          calls.add(
              "end " + target.name() + "#" + target.attempt() + " " + outcome + " " + failureType);
        }
      };
    }

    @Override
    public void timerFired(TraceTarget target, Instant startedAt, Instant firedAt) {
      calls.add(
          "timer "
              + describe(target)
              + " after>=100ms "
              + !firedAt.isBefore(startedAt.plusMillis(100)));
    }

    @Override
    public void signalReceived(TraceTarget target, Instant receivedAt) {
      calls.add("signal " + describe(target));
    }

    @Override
    public void workflowFinished(
        TraceTarget target, WorkflowState state, Instant startedAt, Instant closedAt) {
      calls.add("finished " + describe(target) + " " + state + " " + closedAt.isAfter(startedAt));
    }

    private static String describe(TraceTarget target) {
      return target.name()
          + " of "
          + target.workflowId()
          + " ("
          + target.workflowType()
          + ") in "
          + target.context().get("traceparent")
          + " attempt "
          + target.attempt();
    }
  }

  static final class Broken implements WorkflowTracing {
    @Override
    public Map<String, String> capture(String workflowId, String workflowType) {
      throw new IllegalStateException("tracing is down");
    }

    @Override
    public ActivityTrace activityStarted(TraceTarget target) {
      return new ActivityTrace() {
        @Override
        public <T> Callable<T> wrap(Callable<T> attempt) {
          throw new IllegalStateException("tracing is down");
        }

        @Override
        public void end(ActivityOutcome outcome, String failureType) {
          throw new IllegalStateException("tracing is down");
        }
      };
    }

    @Override
    public void timerFired(TraceTarget target, Instant startedAt, Instant firedAt) {
      throw new IllegalStateException("tracing is down");
    }

    @Override
    public void workflowFinished(
        TraceTarget target, WorkflowState state, Instant startedAt, Instant closedAt) {
      throw new IllegalStateException("tracing is down");
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

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
  void emptyTables() throws SQLException {
    try (DurastepEngine migrate = DurastepEngine.builder().dataSource(dataSource).build()) {
      migrate.start();
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE durastep.workflows CASCADE");
    }
  }

  @Test
  void theEngineReportsEveryAttemptTimerSignalAndTheEndWithTheContextCapturedAtStart()
      throws Exception {
    Recorder client = new Recorder("client");
    Recorder engineTracing = new Recorder("engine");
    FlakyShop shop = new FlakyShop();
    try (DurastepEngine engine = engine(engineTracing, shop)) {
      engine.start();
      DurastepClient caller = DurastepClient.connect(dataSource).withTracing(client);
      var handle = caller.start(Order.class, "order-1", "order-1");
      await()
          .atMost(WAIT)
          .until(() -> engineTracing.calls.stream().anyMatch(call -> call.startsWith("timer")));
      caller.signal("order-1", "approve", "approved");

      assertThat(handle.result(WAIT)).isEqualTo("charged in from-client#2, approved");
      await()
          .atMost(WAIT)
          .until(() -> engineTracing.calls.stream().anyMatch(call -> call.startsWith("finished")));
    }

    assertThat(client.calls).containsExactly("capture order-1 Order");
    assertThat(engineTracing.calls)
        .containsExactly(
            "activity Shop.charge of order-1 (Order) in from-client attempt 1",
            "end Shop.charge#1 RETRIED java.lang.IllegalStateException",
            "activity Shop.charge of order-1 (Order) in from-client attempt 2",
            "end Shop.charge#2 SUCCEEDED null",
            "timer sleep PT0.1S of order-1 (Order) in from-client attempt 0 after>=100ms true",
            "signal approve of order-1 (Order) in from-client attempt 0",
            "finished Order of order-1 (Order) in from-client attempt 0 COMPLETED true");
    assertThat(String.join("\n", engineTracing.calls)).doesNotContain("4111", "gateway");
  }

  @Test
  void withoutTracingNothingIsStoredAndAWorkflowStartedWithoutContextStillReportsItsWork()
      throws Exception {
    Recorder engineTracing = new Recorder("engine");
    try (DurastepEngine engine = engine(engineTracing, new FlakyShop())) {
      engine.start();
      DurastepClient.connect(dataSource).start(Order.class, "plain-1", "plain-1");
      assertThat(traceContextOf("plain-1")).isNull();
      await()
          .atMost(WAIT)
          .until(() -> engineTracing.calls.stream().anyMatch(call -> call.startsWith("timer")));
    }
    assertThat(engineTracing.calls)
        .first()
        .isEqualTo("activity Shop.charge of plain-1 (Order) in null attempt 1");
  }

  @Test
  void theEnginesOwnClientCapturesWithTheEnginesTracingAndStoresTheContext() throws Exception {
    Recorder engineTracing = new Recorder("engine");
    try (DurastepEngine engine = engine(engineTracing, new FlakyShop())) {
      engine.start();
      engine.client().start(Order.class, "own-1", "own-1");
      assertThat(traceContextOf("own-1")).isEqualTo("{\"traceparent\": \"from-engine\"}");
    }
    assertThat(engineTracing.calls).startsWith("capture own-1 Order");
  }

  @Test
  void aTracerThatThrowsEverywhereChangesNothing() throws Exception {
    FlakyShop shop = new FlakyShop();
    try (DurastepEngine engine = engine(new Broken(), shop)) {
      engine.start();
      var handle = engine.client().start(Order.class, "broken-tracing-1", "x");
      await()
          .atMost(WAIT)
          .until(() -> engine.client().handle("broken-tracing-1").history().size() >= 6);
      engine.client().signal("broken-tracing-1", "approve", "approved");

      assertThat(handle.result(WAIT)).isEqualTo("charged in null, approved");
      assertThat(traceContextOf("broken-tracing-1")).isNull();
    }
  }

  private static DurastepEngine engine(WorkflowTracing tracing, Shop shop) {
    return DurastepEngine.builder()
        .dataSource(dataSource)
        .workflow(Order.class, Order::new)
        .activities(Shop.class, shop)
        .tracing(tracing)
        .build();
  }

  private static String traceContextOf(String workflowId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT trace_context::text FROM durastep.workflows WHERE id = '"
                    + workflowId
                    + "'")) {
      row.next();
      return row.getString(1);
    }
  }
}
