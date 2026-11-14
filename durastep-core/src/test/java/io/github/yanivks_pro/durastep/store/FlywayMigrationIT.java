package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.TestPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class FlywayMigrationIT {

  private static final String UNIQUE_VIOLATION = "23505";

  private static PostgreSQLContainer postgres;
  private static Flyway flyway;

  @BeforeAll
  static void migrate() {
    postgres = TestPostgres.newContainer();
    postgres.start();
    flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("durastep")
            .locations("classpath:durastep/db/migration")
            .load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void createsTheFiveTablesAndItsHistoryInTheDurastepSchema() throws SQLException {
    assertThat(tablesIn("durastep"))
        .containsExactlyInAnyOrder(
            "workflows", "history_events", "tasks", "timers", "inbox", "flyway_schema_history");
  }

  @Test
  void runningAgainChangesNothing() {
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void allowsAtMostOneDeciderTaskPerWorkflow() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO workflows (id, type, state) VALUES ('order-1', 'Order', 'RUNNING')");
      statement.execute("INSERT INTO tasks (workflow_id, kind) VALUES ('order-1', 'WORKFLOW')");

      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO tasks (workflow_id, kind) VALUES ('order-1', 'WORKFLOW')"))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo(UNIQUE_VIOLATION);
    }
  }

  @Test
  void deletingAnOriginalKeepsItsRerun() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          INSERT INTO workflows (id, type, state, closed_at)
          VALUES ('order-2', 'Order', 'FAILED', now())
          """);
      statement.execute(
          """
          INSERT INTO workflows (id, type, state, rerun_of)
          VALUES ('order-2-rerun-1', 'Order', 'RUNNING', 'order-2')
          """);

      statement.execute("DELETE FROM workflows WHERE id = 'order-2'");

      try (ResultSet rerun =
          statement.executeQuery(
              "SELECT state, rerun_of FROM workflows WHERE id = 'order-2-rerun-1'")) {
        assertThat(rerun.next()).isTrue();
        assertThat(rerun.getString("state")).isEqualTo("RUNNING");
        assertThat(rerun.getString("rerun_of")).isNull();
      }
    }
  }

  private static Connection connect() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO durastep");
    }
    return connection;
  }

  private static List<String> tablesIn(String schema) throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Connection connection = connect();
        ResultSet result =
            connection.getMetaData().getTables(null, schema, "%", new String[] {"TABLE"})) {
      while (result.next()) {
        tables.add(result.getString("TABLE_NAME"));
      }
    }
    return tables;
  }
}
