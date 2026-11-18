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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ZombieIT {

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final List<Process> processes = new ArrayList<>();

  @BeforeAll
  static void startPostgres() throws SQLException {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    execute("CREATE SCHEMA zombie");
    execute(
        "CREATE TABLE zombie.calls (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " what text NOT NULL, pid bigint NOT NULL)");
    execute("CREATE TABLE zombie.charges (idempotency_key text PRIMARY KEY)");
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void killProcesses() throws Exception {
    for (Process process : processes) {
      signal("CONT", process);
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void aFrozenWorkerLosesItsTaskAndItsResultIsRejectedWhenItWakesUp() throws Exception {
    Process first = launch("first");
    await().atMost(Duration.ofSeconds(60)).until(() -> TestPostgres.fullyMigrated(dataSource));
    DurastepEngine client = DurastepEngine.builder().dataSource(dataSource).build();
    client.client().start(ZombieApp.ChargeWorkflow.class, "order-7", "order-7");
    await().atMost(Duration.ofSeconds(30)).until(() -> calls().contains("started " + first.pid()));

    signal("STOP", first);
    Process second = launch("second");

    String result =
        client
            .client()
            .handle("order-7", ZombieApp.ChargeWorkflow.class)
            .result(Duration.ofMinutes(1));
    assertThat(result).isEqualTo("charged order-7");
    assertThat(calls()).contains("started " + second.pid(), "done " + second.pid());

    signal("CONT", first);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> calls().contains("lease-lost " + first.pid()));

    assertThat(calls()).doesNotContain("done " + first.pid());
    assertThat(count("zombie.charges")).isEqualTo(1);
    assertThat(count("durastep.history_events WHERE event_type = 'ACTIVITY_COMPLETED'"))
        .isEqualTo(1);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private Process launch(String name) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Path log = Path.of("target", "zombie-" + name + ".log");
    Files.createDirectories(log.getParent());
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ZombieApp.class.getName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .directory(new File("."))
            .start();
    processes.add(process);
    return process;
  }

  private static void signal(String signal, Process process) throws Exception {
    Process kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).start();
    assertThat(kill.waitFor(10, TimeUnit.SECONDS)).isTrue();
  }

  private static List<String> calls() throws SQLException {
    List<String> calls = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT what, pid FROM zombie.calls ORDER BY id")) {
      while (row.next()) {
        calls.add(row.getString(1) + " " + row.getLong(2));
      }
    }
    return calls;
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
