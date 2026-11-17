package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OperatorCommandsIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  private static volatile boolean fixDeployed;

  static final class NeedsAFix implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      if (!fixDeployed) {
        throw new IllegalStateException("a bug in version 1.4.1");
      }
      return "processed " + input;
    }
  }

  static final class Sleeper implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      ctx.sleep(Duration.ofDays(30));
      return "woke up";
    }
  }

  static final class Echo implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return "echo " + input;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

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
    fixDeployed = false;
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
            .workflow(NeedsAFix.class, NeedsAFix::new)
            .workflow(Sleeper.class, Sleeper::new)
            .workflow(Echo.class, Echo::new)
            .build();
    engine.start();
    client = engine.client();
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void aBlockedWorkflowCanBeTerminatedAndTheAuditTrailSaysWhoAndWhy() throws Exception {
    WorkflowHandle<String> order = client.start(NeedsAFix.class, "order-1", "order-1");
    await().atMost(WAIT).until(() -> order.describe().blocked());

    boolean terminated =
        client.actingAs("alice").handle("order-1", NeedsAFix.class).terminate("unfixable data");

    assertThat(terminated).isTrue();
    assertThatThrownBy(() -> order.result(WAIT))
        .isInstanceOfSatisfying(
            WorkflowCancelledException.class,
            e -> assertThat(e.reason()).contains("terminated by alice: unfixable data"));
    assertThat(order.describe().state()).isEqualTo(WorkflowState.CANCELLED);
    assertThat(order.describe().blocked()).isFalse();
    assertThat(audit("order-1")).containsExactly("terminate by alice: unfixable data");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void terminatingASleepingWorkflowRemovesItsTimer() throws Exception {
    WorkflowHandle<String> sleeper = client.start(Sleeper.class, "sleeper", "x");
    await().atMost(WAIT).until(() -> count("durastep.timers") == 1);

    assertThat(sleeper.terminate("not needed any more")).isTrue();

    assertThat(count("durastep.timers")).isZero();
    assertThat(count("durastep.tasks")).isZero();
    assertThatThrownBy(() -> sleeper.result(WAIT)).isInstanceOf(WorkflowCancelledException.class);
    assertThat(sleeper.terminate("again")).isFalse();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void retryNowRunsAFixedWorkflowAtOnceInsteadOfAfterItsBackoff() throws Exception {
    WorkflowHandle<String> order = client.start(NeedsAFix.class, "order-2", "order-2");
    await().atMost(WAIT).until(() -> order.describe().blocked());
    execute("UPDATE durastep.tasks SET visible_at = now() + interval '1 hour'");
    fixDeployed = true;

    assertThat(order.retryNow("fix deployed in 1.4.2")).isTrue();

    assertThat(order.result(Duration.ofSeconds(5))).isEqualTo("processed order-2");
    assertThat(audit("order-2"))
        .containsExactly(
            "retry-now by " + System.getProperty("user.name") + ": fix deployed in 1.4.2");
    assertThat(order.retryNow("again")).isFalse();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void retryAllBlockedRetriesEveryBlockedWorkflow() throws Exception {
    WorkflowHandle<String> first = client.start(NeedsAFix.class, "order-3", "order-3");
    WorkflowHandle<String> second = client.start(NeedsAFix.class, "order-4", "order-4");
    client.start(Echo.class, "echo-1", "x").result(WAIT);
    await().atMost(WAIT).until(() -> first.describe().blocked() && second.describe().blocked());
    execute("UPDATE durastep.tasks SET visible_at = now() + interval '1 hour'");
    fixDeployed = true;

    assertThat(client.retryAllBlocked("fix deployed")).isEqualTo(2);

    assertThat(first.result(Duration.ofSeconds(5))).isEqualTo("processed order-3");
    assertThat(second.result(Duration.ofSeconds(5))).isEqualTo("processed order-4");
  }

  @Test
  void aFinishedWorkflowCanBeRerunWithTheSameInput() throws Exception {
    client.start(Echo.class, "echo-2", "hello").result(WAIT);

    String rerunId = client.actingAs("carol").rerun("echo-2", "reprocess after data fix");

    assertThat(rerunId).isEqualTo("echo-2-rerun-1");
    assertThat(client.handle(rerunId, Echo.class).result(WAIT)).isEqualTo("echo hello");
    assertThat(audit(rerunId))
        .containsExactly("rerun of echo-2 by carol: reprocess after data fix");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void operatorCommandsNeedAReason() {
    assertThatThrownBy(() -> client.rerun("echo-3", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
  }

  private static List<String> audit(String workflowId) throws SQLException {
    List<String> entries = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT payload->>'action', payload->>'by', payload->>'reason'"
                    + " FROM durastep.history_events WHERE workflow_id = '"
                    + workflowId
                    + "' AND event_type = 'OPERATOR_ACTION' ORDER BY seq")) {
      while (row.next()) {
        entries.add(row.getString(1) + " by " + row.getString(2) + ": " + row.getString(3));
      }
    }
    return entries;
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
