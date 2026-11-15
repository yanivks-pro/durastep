package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ProcessKillIT {

  private static final int SIGKILL_EXIT_CODE = 128 + 9;

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void startPostgres() throws SQLException {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA shop");
      statement.execute(
          "CREATE TABLE shop.calls (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
              + " activity text NOT NULL, idempotency_key text NOT NULL, pid bigint NOT NULL)");
      statement.execute(
          "CREATE TABLE shop.charges (idempotency_key text PRIMARY KEY, cents bigint NOT NULL)");
    }
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void aProcessKilledMidWorkflowIsResumedExactlyByTheNextOne() throws Exception {
    Process first = launchShopApp(ShopApp.HANG_IN_RESERVE_STOCK, "first");
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> calls().contains("reserveStock order-42/activity-4"));

    first.destroyForcibly();
    assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(first.exitValue()).isEqualTo(SIGKILL_EXIT_CODE);

    Process second = launchShopApp(ShopApp.RUN_TO_COMPLETION, "second");
    assertThat(second.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(second.exitValue()).isZero();

    assertThat(calls())
        .containsExactly(
            "chargeCard order-42/activity-2",
            "reserveStock order-42/activity-4",
            "reserveStock order-42/activity-4",
            "sendEmail order-42/activity-6");
    assertThat(distinctPidsThatRan("reserveStock")).isEqualTo(2);
    assertThat(count("shop.charges")).isEqualTo(1);
    String state =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .build()
            .client()
            .handle("order-42", ShopApp.OrderWorkflow.class)
            .result(Duration.ofSeconds(5));
    assertThat(state).isEqualTo("ch_order-42");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private static Process launchShopApp(String mode, String name) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Path log = Path.of("target", "process-kill-" + name + ".log");
    Files.createDirectories(log.getParent());
    List<String> command =
        List.of(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            ShopApp.class.getName(),
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            mode);
    return new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .directory(new File("."))
        .start();
  }

  private static List<String> calls() throws SQLException {
    List<String> calls = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT activity, idempotency_key FROM shop.calls ORDER BY id")) {
      while (row.next()) {
        calls.add(row.getString(1) + " " + row.getString(2));
      }
    }
    return calls;
  }

  private static int distinctPidsThatRan(String activity) throws SQLException {
    return count("(SELECT DISTINCT pid FROM shop.calls WHERE activity = '" + activity + "') p");
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
