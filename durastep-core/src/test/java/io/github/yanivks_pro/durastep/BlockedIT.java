package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class BlockedIT {

  private static final Duration WAIT = Duration.ofSeconds(30);

  interface Warehouse {
    void pack(String orderId);

    void label(String orderId);

    void ship(String orderId);
  }

  static final class RecordingWarehouse implements Warehouse {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void pack(String orderId) {
      record("pack");
    }

    @Override
    public void label(String orderId) {
      record("label");
    }

    @Override
    public void ship(String orderId) {
      record("ship");
    }

    private void record(String activity) {
      calls.add(activity + " " + ActivityContext.current().idempotencyKey());
    }
  }

  static volatile boolean deployedWithBug;
  static volatile boolean deployedWithLabelFirst;
  static volatile boolean addressUnknown;

  static final class ShipWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      Warehouse warehouse = ctx.activities(Warehouse.class);
      if (deployedWithLabelFirst) {
        warehouse.label(orderId);
      }
      warehouse.pack(orderId);
      if (deployedWithBug) {
        String customer = null;
        return customer.trim();
      }
      if (addressUnknown) {
        throw new WorkflowFailedException("the address of " + orderId + " is unknown");
      }
      ctx.sleep(Duration.ofMillis(500));
      warehouse.ship(orderId);
      return "shipped " + orderId;
    }
  }

  static final class ReturnWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      return "returned " + orderId;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final RecordingWarehouse warehouse = new RecordingWarehouse();
  private final List<DurastepEngine> engines = new ArrayList<>();

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
  void resetCodeAndTables() throws SQLException {
    deployedWithBug = false;
    deployedWithLabelFirst = false;
    addressUnknown = false;
    execute("CREATE SCHEMA IF NOT EXISTS durastep");
    execute(
        "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
            + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
  }

  @AfterEach
  void stopEngines() {
    engines.forEach(DurastepEngine::close);
  }

  @Test
  void aBugBlocksTheWorkflowAndItResumesOnceTheFixIsDeployed() throws Exception {
    deployedWithBug = true;
    DurastepEngine engine = startEngine(true);
    engine.client().start(ShipWorkflow.class, "order-1", "order-1");

    await()
        .atMost(WAIT)
        .until(() -> count("durastep.tasks WHERE kind = 'WORKFLOW' AND attempt >= 3") == 1);
    assertThat(count("durastep.workflows WHERE state = 'RUNNING' AND blocked")).isEqualTo(1);
    assertThat(
            count(
                "durastep.workflows WHERE blocked_error->>'type' = 'java.lang.NullPointerException'"
                    + " AND blocked_error->'stackTrace'->>0 LIKE '%ShipWorkflow.run%'"))
        .isEqualTo(1);
    assertThat(eventTypes("order-1")).containsExactly("WORKFLOW_STARTED", "ACTIVITY_SCHEDULED");
    assertThat(count("durastep.inbox WHERE kind = 'ACTIVITY_COMPLETED'")).isEqualTo(1);
    assertThat(warehouse.calls).containsExactly("pack order-1/activity-2");

    deployedWithBug = false;

    assertThat(engine.client().handle("order-1", ShipWorkflow.class).result(WAIT))
        .isEqualTo("shipped order-1");
    assertThat(warehouse.calls)
        .containsExactly("pack order-1/activity-2", "ship order-1/activity-6");
    assertThat(count("durastep.workflows WHERE NOT blocked AND blocked_error IS NULL"))
        .isEqualTo(1);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aWorkflowFailedExceptionFailsTheWorkflowInsteadOfBlockingIt() throws Exception {
    addressUnknown = true;
    DurastepEngine engine = startEngine(true);

    assertThatThrownBy(
            () -> engine.client().start(ShipWorkflow.class, "order-2", "order-2").result(WAIT))
        .isInstanceOfSatisfying(
            WorkflowFailedException.class,
            failed -> {
              assertThat(failed.failureType()).isEqualTo(WorkflowFailedException.class.getName());
              assertThat(failed.getMessage()).contains("the address of order-2 is unknown");
            });
    assertThat(count("durastep.workflows WHERE blocked")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void codeThatNoLongerMatchesTheHistoryBlocksTheWorkflowWithoutChangingIt() throws Exception {
    DurastepEngine engine = startEngine(true);
    engine.client().start(ShipWorkflow.class, "order-3", "order-3");
    await().atMost(WAIT).until(() -> eventTypes("order-3").contains("TIMER_STARTED"));
    deployedWithLabelFirst = true;

    await().atMost(WAIT).until(() -> count("durastep.workflows WHERE blocked") == 1);
    List<String> historyWhileBlocked = eventTypes("order-3");
    assertThat(
            count(
                "durastep.workflows WHERE blocked_error->>'type'"
                    + " = 'io.github.yanivks_pro.durastep.api.NonDeterminismException'"))
        .isEqualTo(1);
    assertThat(historyWhileBlocked)
        .containsExactly(
            "WORKFLOW_STARTED", "ACTIVITY_SCHEDULED", "ACTIVITY_COMPLETED", "TIMER_STARTED");
    assertThat(count("durastep.inbox WHERE kind = 'TIMER_FIRED'")).isEqualTo(1);
    assertThat(warehouse.calls).containsExactly("pack order-3/activity-2");

    deployedWithLabelFirst = false;

    assertThat(engine.client().handle("order-3", ShipWorkflow.class).result(WAIT))
        .isEqualTo("shipped order-3");
    assertThat(eventTypes("order-3")).startsWith(historyWhileBlocked.toArray(String[]::new));
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aWorkflowTypeThatOnlyANewerEngineKnowsWaitsBlockedUntilItIsDeployed() throws Exception {
    DurastepEngine oldEngine = startEngine(false);
    oldEngine.client().start(ReturnWorkflow.class, "return-1", "order-4");

    await().atMost(WAIT).until(() -> count("durastep.workflows WHERE blocked") == 1);
    assertThat(
            count(
                "durastep.workflows WHERE blocked_error->>'message'"
                    + " = 'workflow type ReturnWorkflow is not registered with this engine'"))
        .isEqualTo(1);
    oldEngine.close();
    engines.remove(oldEngine);

    DurastepEngine newEngine = startEngine(true);

    assertThat(newEngine.client().handle("return-1", ReturnWorkflow.class).result(WAIT))
        .isEqualTo("returned order-4");
    assertThat(count("durastep.workflows WHERE blocked")).isZero();
  }

  private DurastepEngine startEngine(boolean knowsReturns) {
    DurastepEngine.Builder builder =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(ShipWorkflow.class, ShipWorkflow::new)
            .activities(Warehouse.class, warehouse);
    if (knowsReturns) {
      builder.workflow(ReturnWorkflow.class, ReturnWorkflow::new);
    }
    DurastepEngine engine = builder.build();
    engine.start();
    engines.add(engine);
    return engine;
  }

  private static List<String> eventTypes(String workflowId) throws SQLException {
    List<String> types = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT event_type FROM durastep.history_events WHERE workflow_id = '"
                    + workflowId
                    + "' ORDER BY seq")) {
      while (row.next()) {
        types.add(row.getString(1));
      }
    }
    return types;
  }

  private static int count(String from) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT count(*) FROM " + from)) {
      row.next();
      return row.getInt(1);
    }
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
