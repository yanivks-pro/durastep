package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class NotifyIT {

  private static final Duration FAST = Duration.ofSeconds(2);

  interface Steps {
    int step(int number);
  }

  static final class FiveSteps implements Workflow<String, Integer> {
    @Override
    public Integer run(WorkflowContext ctx, String id) {
      Steps steps = ctx.activities(Steps.class);
      int total = 0;
      for (int i = 1; i <= 5; i++) {
        total += steps.step(i);
      }
      return total;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

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
  void startEngine() throws Exception {
    execute("CREATE SCHEMA IF NOT EXISTS durastep");
    execute(
        "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
            + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(FiveSteps.class, FiveSteps::new)
            .activities(Steps.class, number -> number)
            .build();
    engine.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> listeningSessions() == 1);
    Thread.sleep(Duration.ofMillis(1_500));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void idleWorkersAreWokenAtOnceInsteadOfWaitingForTheNextPoll() throws Exception {
    Duration took = timeOneWorkflow("fast-1");

    assertThat(took).isLessThan(FAST);
  }

  @Test
  void theListenerReconnectsWhenItsConnectionIsKilled() throws Exception {
    execute(
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
            + " WHERE query LIKE 'LISTEN%' AND pid <> pg_backend_pid()");
    await().atMost(Duration.ofSeconds(10)).until(() -> listeningSessions() == 0);

    await().atMost(Duration.ofSeconds(10)).until(() -> listeningSessions() == 1);
    Thread.sleep(Duration.ofMillis(1_500));

    assertThat(timeOneWorkflow("fast-2")).isLessThan(FAST);
  }

  private Duration timeOneWorkflow(String id) throws Exception {
    long started = System.nanoTime();
    int total = engine.client().start(FiveSteps.class, id, id).result(Duration.ofSeconds(30));
    assertThat(total).isEqualTo(15);
    return Duration.ofNanos(System.nanoTime() - started);
  }

  private static int listeningSessions() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'LISTEN%'"
                    + " AND pid <> pg_backend_pid()")) {
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
