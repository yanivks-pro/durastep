package io.github.yanivks_pro.durastep.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.InvariantChecker;
import io.github.yanivks_pro.durastep.TestPostgres;
import io.github.yanivks_pro.durastep.activity.ActivityRegistry;
import io.github.yanivks_pro.durastep.activity.ActivityRuntime;
import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.WorkflowDefinition;
import io.github.yanivks_pro.durastep.runtime.WorkflowRegistry;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import io.github.yanivks_pro.durastep.store.CrashPoint;
import io.github.yanivks_pro.durastep.store.CrashPoints;
import io.github.yanivks_pro.durastep.store.DatabaseHealth;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CrashPointIT {

  private static final Duration LEASE = Duration.ofSeconds(1);
  private static final Duration POLL = Duration.ofMillis(20);
  private static final Duration RECOVERY = Duration.ofSeconds(15);

  record Order(String id, long cents) {}

  interface OrderActivities {
    String chargeCard(String orderId, long cents);

    void reserveStock(String orderId);
  }

  static final class OrderWorkflow implements Workflow<Order, String> {
    @Override
    public String run(WorkflowContext ctx, Order order) {
      OrderActivities activities =
          ctx.activities(
              OrderActivities.class,
              ActivityOptions.builder().attemptTimeout(Duration.ofSeconds(2)).build());
      String chargeId = activities.chargeCard(order.id(), order.cents());
      activities.reserveStock(order.id());
      return chargeId;
    }
  }

  static final class Shop implements OrderActivities {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, Long> chargesByKey = new ConcurrentHashMap<>();

    @Override
    public String chargeCard(String orderId, long cents) {
      String key = ActivityContext.current().idempotencyKey();
      calls.add("chargeCard " + key);
      chargesByKey.putIfAbsent(key, cents);
      return "ch_" + key;
    }

    @Override
    public void reserveStock(String orderId) {
      calls.add("reserveStock " + ActivityContext.current().idempotencyKey());
    }
  }

  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedCrash(CrashPoint point) {
      super("simulated crash at " + point);
    }
  }

  static final class CrashOnce implements CrashPoints {
    private final CrashPoint target;
    private final AtomicBoolean fired = new AtomicBoolean();

    CrashOnce(CrashPoint target) {
      this.target = target;
    }

    @Override
    public void reached(CrashPoint point) {
      if (point == target && fired.compareAndSet(false, true)) {
        throw new SimulatedCrash(point);
      }
    }

    boolean fired() {
      return fired.get();
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final Shop shop = new Shop();
  private final List<Worker> workers = new ArrayList<>();
  private final JdbcWorkflowStore plainStore = new JdbcWorkflowStore(dataSource);

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("durastep")
        .locations("classpath:durastep/db/migration")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @BeforeEach
  void emptyTables() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE durastep.workflows CASCADE");
    }
  }

  @AfterEach
  void stopWorkers() throws InterruptedException {
    for (Worker worker : workers) {
      worker.stop(Duration.ofSeconds(5));
    }
  }

  @Test
  void aCrashWhileStartingSavesNothingAndStartingAgainWorks() throws Exception {
    JdbcWorkflowStore crashing =
        new JdbcWorkflowStore(
            dataSource, JdbcWorkflowStore.DEFAULT_SCHEMA, new CrashOnce(CrashPoint.MID_START));

    assertThatThrownBy(() -> startOrder(crashing)).isInstanceOf(SimulatedCrash.class);
    assertThat(count("workflows")).isZero();
    assertThat(count("history_events")).isZero();
    assertThat(count("tasks")).isZero();

    startOrder(plainStore);
    startWorker("worker-b", CrashPoints.NONE);
    awaitCompleted();
    assertThat(shop.calls)
        .containsExactly("chargeCard order-42/activity-2", "reserveStock order-42/activity-4");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aCrashInTheMiddleOfSavingADecisionSavesNothingOfIt() throws Exception {
    CrashOnce crash = new CrashOnce(CrashPoint.MID_DECISION_COMMIT);
    startOrder(plainStore);
    Worker workerA = startWorker("worker-a", crash);

    await().dontCatchUncaughtExceptions().atMost(RECOVERY).until(crash::fired);
    workerA.stop(Duration.ofSeconds(5));
    assertThat(eventTypes()).containsExactly("WORKFLOW_STARTED");
    assertThat(count("tasks WHERE kind = 'ACTIVITY'")).isZero();
    assertThat(count("tasks WHERE kind = 'WORKFLOW'")).isEqualTo(1);

    startWorker("worker-b", CrashPoints.NONE);
    awaitCompleted();
    assertThat(shop.calls)
        .containsExactly("chargeCard order-42/activity-2", "reserveStock order-42/activity-4");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aCrashAfterAnActivityRanRunsItAgainWithTheSameKeyAndChargesOnce() throws Exception {
    CrashOnce crash = new CrashOnce(CrashPoint.AFTER_ACTIVITY_RAN);
    startOrder(plainStore);
    Worker workerA = startWorker("worker-a", crash);

    await().dontCatchUncaughtExceptions().atMost(RECOVERY).until(crash::fired);
    workerA.stop(Duration.ofSeconds(5));
    assertThat(shop.calls).containsExactly("chargeCard order-42/activity-2");
    assertThat(count("inbox")).isZero();

    startWorker("worker-b", CrashPoints.NONE);
    awaitCompleted();
    assertThat(shop.calls)
        .containsExactly(
            "chargeCard order-42/activity-2",
            "chargeCard order-42/activity-2",
            "reserveStock order-42/activity-4");
    assertThat(shop.chargesByKey).containsOnlyKeys("order-42/activity-2");
    assertThat(eventTypes())
        .containsExactly(
            "WORKFLOW_STARTED",
            "ACTIVITY_SCHEDULED",
            "ACTIVITY_COMPLETED",
            "ACTIVITY_SCHEDULED",
            "ACTIVITY_COMPLETED",
            "WORKFLOW_COMPLETED");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aCrashWhileSavingAnActivityResultLosesNothing() throws Exception {
    CrashOnce crash = new CrashOnce(CrashPoint.MID_ACTIVITY_COMPLETE);
    startOrder(plainStore);
    Worker workerA = startWorker("worker-a", crash);

    await().dontCatchUncaughtExceptions().atMost(RECOVERY).until(crash::fired);
    workerA.stop(Duration.ofSeconds(5));
    assertThat(count("inbox")).isZero();
    assertThat(count("tasks WHERE kind = 'ACTIVITY'")).isEqualTo(1);

    startWorker("worker-b", CrashPoints.NONE);
    awaitCompleted();
    assertThat(shop.chargesByKey).containsOnlyKeys("order-42/activity-2");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private void startOrder(JdbcWorkflowStore store) {
    store.start("order-42", "OrderWorkflow", json.toPayload(new Order("order-42", 4200)));
  }

  private Worker startWorker(String owner, CrashPoints crashPoints) {
    JdbcWorkflowStore store =
        new JdbcWorkflowStore(dataSource, JdbcWorkflowStore.DEFAULT_SCHEMA, crashPoints);
    WorkflowRegistry workflows =
        new WorkflowRegistry()
            .register(WorkflowDefinition.of(OrderWorkflow.class, OrderWorkflow::new));
    ActivityRegistry activities = new ActivityRegistry().register(OrderActivities.class, shop);
    Worker worker =
        new Worker(
            store,
            workflows,
            new WorkflowRuntime(json),
            new ActivityRuntime(activities, json),
            new Worker.Settings(owner, LEASE, POLL, 4, 4),
            crashPoints,
            new DatabaseHealth());
    worker.start();
    workers.add(worker);
    return worker;
  }

  private void awaitCompleted() {
    await()
        .dontCatchUncaughtExceptions()
        .atMost(RECOVERY)
        .until(
            () ->
                plainStore
                    .find("order-42")
                    .map(w -> w.state() == WorkflowState.COMPLETED)
                    .orElse(false));
  }

  private static List<String> eventTypes() throws Exception {
    List<String> types = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT event_type FROM durastep.history_events ORDER BY workflow_id, seq")) {
      while (row.next()) {
        types.add(row.getString(1));
      }
    }
    return types;
  }

  private static int count(String tableAndCondition) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery("SELECT count(*) FROM durastep." + tableAndCondition)) {
      row.next();
      return row.getInt(1);
    }
  }
}
