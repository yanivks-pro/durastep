package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.worker.Retention;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RetentionIT {

  private static final Duration THIRTY_DAYS = Duration.ofDays(30);

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final JdbcWorkflowStore store = new JdbcWorkflowStore(dataSource);

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
  void emptyTables() throws SQLException {
    execute("TRUNCATE durastep.workflows CASCADE");
  }

  @Test
  void onlyFinishedWorkflowsOlderThanTheRetentionAreDeletedWithEverythingThatBelongsToThem()
      throws SQLException {
    finished("old-completed", "COMPLETED", 40);
    finished("old-failed", "FAILED", 40);
    finished("old-cancelled", "CANCELLED", 40);
    finished("recent-completed", "COMPLETED", 1);
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, created_at)"
            + " VALUES ('old-running', 'Order', 'RUNNING', 1, now() - interval '90 days')");
    execute(
        "INSERT INTO durastep.history_events (workflow_id, seq, event_type)"
            + " VALUES ('old-running', 1, 'WORKFLOW_STARTED')");
    execute(
        "INSERT INTO durastep.timers (workflow_id, seq, fire_at) VALUES ('old-failed', 3, now())");

    int deleted = retention(500, 20).runOnce();

    assertThat(deleted).isEqualTo(3);
    assertThat(ids("durastep.workflows")).containsExactly("old-running", "recent-completed");
    assertThat(ids("durastep.history_events")).containsExactly("old-running", "recent-completed");
    assertThat(count("durastep.timers")).isZero();
  }

  @Test
  void aRerunOutlivesItsDeletedOriginalAndLosesOnlyTheLink() throws SQLException {
    finished("order-1", "COMPLETED", 40);
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, rerun_of)"
            + " VALUES ('order-1-rerun-1', 'Order', 'RUNNING', 2, 'order-1')");

    retention(500, 20).runOnce();

    assertThat(ids("durastep.workflows")).containsExactly("order-1-rerun-1");
    assertThat(count("durastep.workflows WHERE rerun_of IS NULL")).isEqualTo(1);
  }

  @Test
  void aLargeBacklogIsDeletedInSmallBatchesSpreadOverSeveralRuns() throws SQLException {
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, closed_at)"
            + " SELECT 'old-' || n, 'Order', 'COMPLETED', 1, now() - interval '40 days'"
            + " FROM generate_series(1, 1200) n");
    Retention twoBatchesPerRun = retention(500, 2);

    assertThat(twoBatchesPerRun.runOnce()).isEqualTo(1_000);
    assertThat(twoBatchesPerRun.runOnce()).isEqualTo(200);
    assertThat(twoBatchesPerRun.runOnce()).isZero();
  }

  @Test
  void whileAnotherEngineIsDeletingThisOneSkipsItsTurn() throws SQLException {
    finished("old-completed", "COMPLETED", 40);

    try (Connection otherEngine = dataSource.getConnection();
        Statement statement = otherEngine.createStatement()) {
      otherEngine.setAutoCommit(false);
      statement.execute("SELECT pg_advisory_xact_lock(hashtext('durastep retention durastep'))");

      assertThat(retention(500, 20).runOnce()).isZero();
      otherEngine.commit();
    }
    assertThat(retention(500, 20).runOnce()).isEqualTo(1);
  }

  @Test
  void anEngineDeletesOldWorkflowsShortlyAfterItStartsUnlessRetentionIsOff() throws Exception {
    finished("old-completed", "COMPLETED", 40);

    try (DurastepEngine keepsForever =
        DurastepEngine.builder().dataSource(dataSource).noRetention().build()) {
      keepsForever.start();
      Thread.sleep(Duration.ofSeconds(4));
      assertThat(count("durastep.workflows")).isEqualTo(1);
    }
    try (DurastepEngine engine = DurastepEngine.builder().dataSource(dataSource).build()) {
      engine.start();
      await().atMost(Duration.ofSeconds(15)).until(() -> count("durastep.workflows") == 0);
    }
  }

  private Retention retention(int batchSize, int maxBatches) {
    return new Retention(
        store,
        new Retention.Settings(
            THIRTY_DAYS, Duration.ZERO, Duration.ofMinutes(10), batchSize, maxBatches));
  }

  private static void finished(String workflowId, String state, int daysAgo) throws SQLException {
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, closed_at)"
            + " VALUES ('"
            + workflowId
            + "', 'Order', '"
            + state
            + "', 1, now() - interval '"
            + daysAgo
            + " days')");
    execute(
        "INSERT INTO durastep.history_events (workflow_id, seq, event_type) VALUES ('"
            + workflowId
            + "', 1, 'WORKFLOW_STARTED')");
  }

  private static List<String> ids(String table) throws SQLException {
    String column = table.endsWith("workflows") ? "id" : "workflow_id";
    List<String> ids = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT DISTINCT " + column + " FROM " + table + " ORDER BY " + column)) {
      while (row.next()) {
        ids.add(row.getString(1));
      }
    }
    return ids;
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
