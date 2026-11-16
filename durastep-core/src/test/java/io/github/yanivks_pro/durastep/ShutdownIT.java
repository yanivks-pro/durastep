package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ShutdownIT {

  interface Exports {
    String export(String id);
  }

  static final class SlowExport implements Exports {
    final AtomicInteger started = new AtomicInteger();
    final AtomicInteger finished = new AtomicInteger();
    final AtomicBoolean interrupted = new AtomicBoolean();
    volatile long firstCallMillis;

    @Override
    public String export(String id) {
      int call = started.incrementAndGet();
      try {
        Thread.sleep(call == 1 ? firstCallMillis : 0);
      } catch (InterruptedException e) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      finished.incrementAndGet();
      return "exported " + id;
    }
  }

  static final class ExportWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String id) {
      return ctx.activities(
              Exports.class,
              ActivityOptions.builder()
                  .attemptTimeout(Duration.ofMinutes(2))
                  .retryPolicy(RetryPolicy.none())
                  .build())
          .export(id);
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final SlowExport exports = new SlowExport();
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
  void emptyTables() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
  }

  @AfterEach
  void stopEngines() {
    engines.forEach(DurastepEngine::close);
  }

  @Test
  void workInProgressFinishesDuringTheGracePeriodAndItsResultIsSaved() throws Exception {
    exports.firstCallMillis = 2_000;
    DurastepEngine engine = start(Duration.ofSeconds(10));
    engine.client().start(ExportWorkflow.class, "export-1", "export-1");
    await().atMost(Duration.ofSeconds(10)).until(() -> exports.started.get() == 1);

    long stopping = System.nanoTime();
    engine.close();
    Duration stopTook = Duration.ofNanos(System.nanoTime() - stopping);

    assertThat(stopTook).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(8));
    assertThat(exports.finished.get()).isEqualTo(1);
    assertThat(exports.interrupted).isFalse();
    assertThat(count("durastep.inbox WHERE kind = 'ACTIVITY_COMPLETED'")).isEqualTo(1);

    DurastepEngine next = start(Duration.ofSeconds(10));
    assertThat(
            next.client().handle("export-1", ExportWorkflow.class).result(Duration.ofSeconds(20)))
        .isEqualTo("exported export-1");
    assertThat(exports.started.get()).isEqualTo(1);
  }

  @Test
  void unfinishedWorkIsReleasedAfterTheGracePeriodAndTakenOverAtOnce() throws Exception {
    exports.firstCallMillis = 60_000;
    DurastepEngine stopping = start(Duration.ofSeconds(1));
    stopping.client().start(ExportWorkflow.class, "export-2", "export-2");
    await().atMost(Duration.ofSeconds(10)).until(() -> exports.started.get() == 1);
    DurastepEngine other = start(Duration.ofSeconds(10));

    long closing = System.nanoTime();
    stopping.close();
    engines.remove(stopping);
    String result =
        other.client().handle("export-2", ExportWorkflow.class).result(Duration.ofSeconds(30));
    Duration takenOverAfter = Duration.ofNanos(System.nanoTime() - closing);

    assertThat(result).isEqualTo("exported export-2");
    assertThat(takenOverAfter).isLessThan(Duration.ofSeconds(6));
    assertThat(exports.interrupted).isTrue();
    assertThat(exports.started.get()).isEqualTo(2);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private DurastepEngine start(Duration grace) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .shutdownGrace(grace)
            .workflow(ExportWorkflow.class, ExportWorkflow::new)
            .activities(Exports.class, exports)
            .build();
    engine.start();
    engines.add(engine);
    return engine;
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
