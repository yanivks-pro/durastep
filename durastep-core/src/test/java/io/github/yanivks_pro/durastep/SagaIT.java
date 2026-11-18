package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
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

class SagaIT {

  private static final int SIGKILL_EXIT_CODE = 128 + 9;

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
    execute("CREATE SCHEMA trip");
    execute(
        "CREATE TABLE trip.calls (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " activity text NOT NULL, idempotency_key text NOT NULL, pid bigint NOT NULL)");
    execute("CREATE TABLE trip.effects (idempotency_key text PRIMARY KEY, activity text NOT NULL)");
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void stopProcesses() throws Exception {
    for (Process process : processes) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void aProcessKilledWhileUndoingATripIsReplacedAndEveryBookingIsUndoneExactlyOnce()
      throws Exception {
    Process first = launch(TripApp.HANG_WHILE_CANCELLING_HOTEL, "first");
    await().atMost(Duration.ofSeconds(60)).until(() -> TestPostgres.fullyMigrated(dataSource));
    DurastepClient client = DurastepEngine.builder().dataSource(dataSource).build().client();
    client.start(TripApp.TripWorkflow.class, "trip-1", "trip-1");
    await().atMost(Duration.ofSeconds(30)).until(() -> activities().contains("cancelHotel"));

    first.destroyForcibly();
    assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(first.exitValue()).isEqualTo(SIGKILL_EXIT_CODE);
    launch(TripApp.RUN_NORMALLY, "second");

    assertThatThrownBy(
            () -> client.handle("trip-1", TripApp.TripWorkflow.class).result(Duration.ofMinutes(1)))
        .isInstanceOf(WorkflowFailedException.class)
        .hasMessageContaining("no cars left for trip-1");
    assertThat(activities())
        .containsExactly(
            "bookFlight", "bookHotel", "bookCar", "cancelHotel", "cancelHotel", "cancelFlight");
    assertThat(
            count(
                "(SELECT DISTINCT idempotency_key FROM trip.calls WHERE activity = 'cancelHotel') k"))
        .isEqualTo(1);
    assertThat(effects()).containsExactly("bookFlight", "bookHotel", "cancelHotel", "cancelFlight");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private Process launch(String mode, String name) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    Path log = Path.of("target", "saga-" + name + ".log");
    Files.createDirectories(log.getParent());
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                TripApp.class.getName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .directory(new File("."))
            .start();
    processes.add(process);
    return process;
  }

  private static List<String> activities() throws SQLException {
    return strings("SELECT activity FROM trip.calls ORDER BY id");
  }

  private static List<String> effects() throws SQLException {
    return strings(
        "SELECT e.activity FROM trip.effects e JOIN (SELECT idempotency_key, min(id) AS first"
            + " FROM trip.calls GROUP BY idempotency_key) c USING (idempotency_key) ORDER BY c.first");
  }

  private static List<String> strings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(sql)) {
      while (row.next()) {
        values.add(row.getString(1));
      }
    }
    return values;
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
