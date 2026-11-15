package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class InvariantCheckerIT {

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
  void aHealthyWorkflowHasNoViolations() throws SQLException {
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, closed_at)"
            + " VALUES ('ok', 'T', 'COMPLETED', 4, now())");
    insertEvent("ok", 1, "WORKFLOW_STARTED", "{}");
    insertEvent("ok", 2, "ACTIVITY_SCHEDULED", "{\"activity\": \"A.a\", \"arguments\": []}");
    insertEvent("ok", 3, "ACTIVITY_COMPLETED", "{\"scheduledSeq\": 2, \"result\": null}");
    insertEvent("ok", 4, "WORKFLOW_COMPLETED", "null");

    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void reportsAnActivityWithTwoResultsAndAGapInHistory() throws SQLException {
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length)"
            + " VALUES ('bad', 'T', 'RUNNING', 4)");
    insertEvent("bad", 1, "WORKFLOW_STARTED", "{}");
    insertEvent("bad", 2, "ACTIVITY_SCHEDULED", "{\"activity\": \"A.a\", \"arguments\": []}");
    insertEvent("bad", 3, "ACTIVITY_COMPLETED", "{\"scheduledSeq\": 2, \"result\": 1}");
    insertEvent("bad", 5, "ACTIVITY_COMPLETED", "{\"scheduledSeq\": 2, \"result\": 2}");

    assertThat(InvariantChecker.violations(dataSource))
        .contains(
            "history is numbered 1..n without gaps: bad",
            "an activity has at most one result: bad/2");
  }

  @Test
  void reportsAFinishedWorkflowThatStillHasATask() throws SQLException {
    execute(
        "INSERT INTO durastep.workflows (id, type, state, history_length, closed_at)"
            + " VALUES ('done', 'T', 'FAILED', 2, now())");
    insertEvent("done", 1, "WORKFLOW_STARTED", "{}");
    insertEvent("done", 2, "WORKFLOW_FAILED", "{\"type\": \"E\", \"message\": \"m\"}");
    execute("INSERT INTO durastep.tasks (workflow_id, kind) VALUES ('done', 'WORKFLOW')");

    assertThat(InvariantChecker.violations(dataSource))
        .containsExactly("a finished workflow has no tasks: done");
  }

  private static void insertEvent(String workflowId, int seq, String type, String payload)
      throws SQLException {
    execute(
        "INSERT INTO durastep.history_events (workflow_id, seq, event_type, payload) VALUES ('"
            + workflowId
            + "', "
            + seq
            + ", '"
            + type
            + "', '"
            + payload
            + "')");
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
