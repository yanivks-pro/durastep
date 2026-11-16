package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowOptions;
import io.github.yanivks_pro.durastep.api.WorkflowTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class WorkflowTimeoutIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  record Job(String id, Duration sleep, long workMillis) {}

  interface Worker {
    String work(String id, long millis);
  }

  static final class SlowWorker implements Worker {
    final List<String> finished = new CopyOnWriteArrayList<>();

    @Override
    public String work(String id, long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      finished.add(id);
      return "done " + id;
    }
  }

  static final class JobWorkflow implements Workflow<Job, String> {
    @Override
    public String run(WorkflowContext ctx, Job job) {
      ctx.sleep(job.sleep());
      return ctx.activities(
              Worker.class,
              ActivityOptions.builder()
                  .attemptTimeout(Duration.ofSeconds(10))
                  .retryPolicy(RetryPolicy.none())
                  .build())
          .work(job.id(), job.workMillis());
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final SlowWorker worker = new SlowWorker();
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
    execute("CREATE SCHEMA IF NOT EXISTS durastep");
    execute(
        "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
            + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(JobWorkflow.class, JobWorkflow::new)
            .activities(Worker.class, worker)
            .build();
    engine.start();
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void aWorkflowThatSleepsPastItsDeadlineFailsWithATimeout() throws Exception {
    long started = System.nanoTime();

    assertThatThrownBy(
            () ->
                engine
                    .client()
                    .start(
                        JobWorkflow.class,
                        "job-1",
                        new Job("job-1", Duration.ofMinutes(10), 0),
                        WorkflowOptions.timeout(Duration.ofSeconds(1)))
                    .result(WAIT))
        .isInstanceOfSatisfying(
            WorkflowFailedException.class,
            failed ->
                assertThat(failed.failureType())
                    .isEqualTo(WorkflowTimeoutException.class.getName()));

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(count("durastep.timers")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aWorkflowThatFinishesBeforeItsDeadlineCompletesAndLeavesNoTimer() throws Exception {
    String result =
        engine
            .client()
            .start(
                JobWorkflow.class,
                "job-2",
                new Job("job-2", Duration.ofMillis(100), 0),
                WorkflowOptions.timeout(Duration.ofMinutes(5)))
            .result(WAIT);

    assertThat(result).isEqualTo("done job-2");
    assertThat(count("durastep.timers")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void theDeadlineAlsoEndsAWorkflowWhoseActivityIsStillRunning() throws Exception {
    assertThatThrownBy(
            () ->
                engine
                    .client()
                    .start(
                        JobWorkflow.class,
                        "job-3",
                        new Job("job-3", Duration.ZERO, 4_000),
                        WorkflowOptions.timeout(Duration.ofSeconds(1)))
                    .result(WAIT))
        .isInstanceOf(WorkflowFailedException.class)
        .hasMessageContaining("did not finish before its deadline");

    Thread.sleep(Duration.ofSeconds(4));
    assertThat(worker.finished).containsExactly("job-3");
    assertThat(count("durastep.history_events WHERE event_type = 'ACTIVITY_COMPLETED'")).isZero();
    assertThat(count("durastep.tasks")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
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
