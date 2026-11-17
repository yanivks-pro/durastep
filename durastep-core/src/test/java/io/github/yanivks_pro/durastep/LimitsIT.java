package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.PayloadTooLargeException;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class LimitsIT {

  private static final int PAYLOAD_LIMIT = 1_000;
  private static final int HISTORY_WARN_AT = 20;
  private static final int HISTORY_LIMIT = 40;
  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String TOO_LARGE = "x".repeat(2 * PAYLOAD_LIMIT);

  interface Files {
    String read(String name);

    void write(String content);

    int next(int value);
  }

  static final class FakeFiles implements Files {
    final AtomicInteger reads = new AtomicInteger();

    @Override
    public String read(String name) {
      reads.incrementAndGet();
      return TOO_LARGE;
    }

    @Override
    public void write(String content) {}

    @Override
    public int next(int value) {
      return value + 1;
    }
  }

  static final class Echo implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return ctx.awaitSignal("go", String.class) + input;
    }
  }

  static final class ReadsALargeFile implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      try {
        return ctx.activities(Files.class).read(name);
      } catch (ActivityFailedException e) {
        return "could not read " + name + ": " + e.failureType();
      }
    }
  }

  static final class WritesALargeFile implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      ctx.activities(Files.class).write(TOO_LARGE);
      return "written";
    }
  }

  static final class ReturnsTooMuch implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      return TOO_LARGE;
    }
  }

  static final class CountsForever implements Workflow<String, Integer> {
    @Override
    public Integer run(WorkflowContext ctx, String name) {
      Files files = ctx.activities(Files.class);
      int value = 0;
      while (true) {
        value = files.next(value);
      }
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final FakeFiles files = new FakeFiles();
  private DurastepEngine engine;

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
            .maxPayloadSize(PAYLOAD_LIMIT)
            .historyLimits(HISTORY_WARN_AT, HISTORY_LIMIT)
            .workflow(Echo.class, Echo::new)
            .workflow(ReadsALargeFile.class, ReadsALargeFile::new)
            .workflow(WritesALargeFile.class, WritesALargeFile::new)
            .workflow(ReturnsTooMuch.class, ReturnsTooMuch::new)
            .workflow(CountsForever.class, CountsForever::new)
            .activities(Files.class, files)
            .build();
    engine.start();
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void aTooLargeInputOrSignalIsRejectedAndNothingIsStored() throws Exception {
    assertThatThrownBy(() -> engine.client().start(Echo.class, "echo-1", TOO_LARGE))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessageContaining("pass a reference");
    assertThat(count("durastep.workflows")).isZero();

    WorkflowHandle<String> echo = engine.client().start(Echo.class, "echo-2", "!");
    assertThatThrownBy(() -> echo.signal("go", TOO_LARGE))
        .isInstanceOf(PayloadTooLargeException.class);
    assertThat(count("durastep.inbox")).isZero();
  }

  @Test
  void aTooLargeActivityResultFailsTheActivityOnceAndTheWorkflowCanHandleIt() throws Exception {
    WorkflowHandle<String> reads =
        engine.client().start(ReadsALargeFile.class, "reads-1", "report.csv");

    assertThat(reads.result(WAIT))
        .isEqualTo("could not read report.csv: " + PayloadTooLargeException.class.getName());
    assertThat(files.reads).hasValue(1);
  }

  @Test
  void tooLargeActivityArgumentsOrWorkflowResultsBlockTheWorkflow() throws Exception {
    WorkflowHandle<String> writes =
        engine.client().start(WritesALargeFile.class, "writes-1", "report.csv");
    WorkflowHandle<String> returns =
        engine.client().start(ReturnsTooMuch.class, "returns-1", "report.csv");

    await().atMost(WAIT).until(() -> writes.describe().blocked() && returns.describe().blocked());
    assertThat(blockedError("writes-1")).contains(PayloadTooLargeException.class.getName());
    assertThat(blockedError("returns-1")).contains(PayloadTooLargeException.class.getName());
  }

  @Test
  void aWorkflowWarnsOnceWhenItsHistoryGrowsLongAndIsBlockedAtTheLimit() throws Exception {
    ByteArrayOutputStream logs = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(logs, true, StandardCharsets.UTF_8));
    WorkflowHandle<Integer> counter;
    try {
      counter = engine.client().start(CountsForever.class, "counter-1", "x");
      await().atMost(WAIT).until(() -> counter.describe().blocked());
    } finally {
      System.setErr(originalErr);
    }

    String log = logs.toString(StandardCharsets.UTF_8);
    assertThat(log.split("counter-1 has \\d+ history events; it will be blocked", -1)).hasSize(2);
    assertThat(blockedError("counter-1")).contains("reaches the limit of " + HISTORY_LIMIT);
    assertThat(counter.terminate("history too long")).isTrue();
    assertThatThrownBy(() -> counter.result(WAIT)).isInstanceOf(WorkflowCancelledException.class);
  }

  private static String blockedError(String workflowId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT blocked_error::text FROM durastep.workflows WHERE id = '"
                    + workflowId
                    + "'")) {
      row.next();
      return row.getString(1);
    }
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
