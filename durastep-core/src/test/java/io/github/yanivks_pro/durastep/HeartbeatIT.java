package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityLeaseLostException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class HeartbeatIT {

  interface Imports {
    String importFile(String id);
  }

  static final class ImportJob implements Imports {
    final AtomicInteger attempts = new AtomicInteger();
    final List<String> leaseLost = new CopyOnWriteArrayList<>();
    volatile long firstAttemptSilentMillis;
    volatile long heartbeatingMillis;

    @Override
    public String importFile(String id) {
      int attempt = attempts.incrementAndGet();
      if (attempt == 1 && firstAttemptSilentMillis > 0) {
        sleep(firstAttemptSilentMillis);
        try {
          ActivityContext.current().heartbeat();
        } catch (ActivityLeaseLostException e) {
          leaseLost.add(e.getMessage());
          throw e;
        }
      }
      long end = System.nanoTime() + Duration.ofMillis(heartbeatingMillis).toNanos();
      while (System.nanoTime() < end) {
        sleep(100);
        ActivityContext.current().heartbeat();
      }
      return "imported " + id + " on attempt " + attempt;
    }

    private static void sleep(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  static final class ImportWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String id) {
      return ctx.activities(
              Imports.class,
              ActivityOptions.builder()
                  .attemptTimeout(Duration.ofMinutes(1))
                  .heartbeatTimeout(Duration.ofSeconds(1))
                  .retryPolicy(RetryPolicy.none())
                  .build())
          .importFile(id);
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final ImportJob job = new ImportJob();
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
  void startTwoEngines() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
    for (int i = 0; i < 2; i++) {
      DurastepEngine engine =
          DurastepEngine.builder()
              .dataSource(dataSource)
              .leaseDuration(Duration.ofSeconds(1))
              .workflow(ImportWorkflow.class, ImportWorkflow::new)
              .activities(Imports.class, job)
              .build();
      engine.start();
      engines.add(engine);
    }
  }

  @AfterEach
  void stopEngines() {
    engines.forEach(DurastepEngine::close);
  }

  @Test
  void anActivityThatKeepsHeartbeatingIsNotTakenOverEvenWhenItRunsLongerThanItsTimeout()
      throws Exception {
    job.heartbeatingMillis = 4_000;

    String result =
        engines
            .getFirst()
            .client()
            .start(ImportWorkflow.class, "import-1", "import-1")
            .result(Duration.ofSeconds(20));

    assertThat(result).isEqualTo("imported import-1 on attempt 1");
    assertThat(job.attempts.get()).isEqualTo(1);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aSilentAttemptIsTakenOverAfterItsHeartbeatTimeoutNotItsAttemptTimeout() throws Exception {
    job.firstAttemptSilentMillis = 4_000;
    long started = System.nanoTime();

    String result =
        engines
            .getFirst()
            .client()
            .start(ImportWorkflow.class, "import-2", "import-2")
            .result(Duration.ofSeconds(20));
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertThat(result).isEqualTo("imported import-2 on attempt 2");
    assertThat(took).isLessThan(Duration.ofSeconds(4));
    await().atMost(Duration.ofSeconds(10)).until(() -> job.leaseLost.size() == 1);
    assertThat(job.leaseLost.getFirst()).contains("import-2/activity-2 was taken over");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }
}
