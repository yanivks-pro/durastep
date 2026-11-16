package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ConcurrencyIT {

  private static final int WORKFLOWS = 200;
  private static final int STEPS = 5;

  interface Steps {
    int step(String workflowId, int number);
  }

  static final Map<String, AtomicInteger> runsByKey = new ConcurrentHashMap<>();
  static final Map<String, AtomicInteger> decidingNow = new ConcurrentHashMap<>();
  static final AtomicInteger mostDecisionsAtOnceForOneWorkflow = new AtomicInteger();

  static final class CountingSteps implements Steps {
    final AtomicInteger ranHere = new AtomicInteger();

    @Override
    public int step(String workflowId, int number) {
      ranHere.incrementAndGet();
      runsByKey
          .computeIfAbsent(ActivityContext.current().idempotencyKey(), key -> new AtomicInteger())
          .incrementAndGet();
      return number;
    }
  }

  static final class FiveSteps implements Workflow<String, Integer> {
    @Override
    public Integer run(WorkflowContext ctx, String workflowId) {
      AtomicInteger deciding = decidingNow.computeIfAbsent(workflowId, id -> new AtomicInteger());
      mostDecisionsAtOnceForOneWorkflow.accumulateAndGet(deciding.incrementAndGet(), Math::max);
      try {
        Steps steps = ctx.activities(Steps.class);
        int total = 0;
        for (int i = 1; i <= STEPS; i++) {
          total += steps.step(workflowId, i);
        }
        return total;
      } finally {
        deciding.decrementAndGet();
      }
    }
  }

  interface Gate {
    void pass();
  }

  static final class CountingGate implements Gate {
    final AtomicInteger inside = new AtomicInteger();
    final AtomicInteger mostInside = new AtomicInteger();
    final CountDownLatch open = new CountDownLatch(1);

    @Override
    public void pass() {
      mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
      try {
        if (!open.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the gate was never opened");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        inside.decrementAndGet();
      }
    }
  }

  static final class ThroughTheGate implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String id) {
      ctx.activities(Gate.class).pass();
      return id;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final List<DurastepEngine> engines = new ArrayList<>();

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgres.newContainer();
    postgres.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");
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
    runsByKey.clear();
    decidingNow.clear();
    mostDecisionsAtOnceForOneWorkflow.set(0);
    execute("CREATE SCHEMA IF NOT EXISTS durastep");
    execute(
        "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
            + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
  }

  @AfterEach
  void stopEngines() {
    engines.forEach(DurastepEngine::close);
  }

  @Test
  void tenEnginesRunAThousandActivitiesExactlyOnceWithOneDeciderPerWorkflow() throws Exception {
    List<CountingSteps> stepsPerEngine = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      CountingSteps steps = new CountingSteps();
      stepsPerEngine.add(steps);
      engines.add(
          start(
              DurastepEngine.builder()
                  .dataSource(dataSource)
                  .workflowConcurrency(5)
                  .activityConcurrency(10)
                  .workflow(FiveSteps.class, FiveSteps::new)
                  .activities(Steps.class, steps)));
    }
    List<WorkflowHandle<Integer>> handles = new ArrayList<>();
    for (int i = 0; i < WORKFLOWS; i++) {
      handles.add(engines.get(i % 10).client().start(FiveSteps.class, "wf-" + i, "wf-" + i));
    }

    for (WorkflowHandle<Integer> handle : handles) {
      assertThat(handle.result(Duration.ofMinutes(2))).isEqualTo(15);
    }

    assertThat(runsByKey).hasSize(WORKFLOWS * STEPS);
    assertThat(runsByKey.values()).allSatisfy(runs -> assertThat(runs.get()).isEqualTo(1));
    assertThat(mostDecisionsAtOnceForOneWorkflow.get()).isEqualTo(1);
    assertThat(stepsPerEngine).filteredOn(steps -> steps.ranHere.get() > 0).hasSizeGreaterThan(5);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void anEngineNeverRunsOrHoldsMoreActivitiesThanItHasSlotsFor() throws Exception {
    CountingGate gate = new CountingGate();
    DurastepEngine engine =
        start(
            DurastepEngine.builder()
                .dataSource(dataSource)
                .activityConcurrency(3)
                .workflow(ThroughTheGate.class, ThroughTheGate::new)
                .activities(Gate.class, gate));
    engines.add(engine);
    for (int i = 0; i < 10; i++) {
      engine.client().start(ThroughTheGate.class, "gate-" + i, "gate-" + i);
    }

    await().atMost(Duration.ofSeconds(20)).until(() -> gate.inside.get() == 3);
    Thread.sleep(Duration.ofSeconds(1));
    assertThat(gate.mostInside.get()).isEqualTo(3);
    assertThat(count("durastep.tasks WHERE kind = 'ACTIVITY' AND lease_until > now()"))
        .isEqualTo(3);
    assertThat(count("durastep.tasks WHERE kind = 'ACTIVITY' AND lease_owner IS NULL"))
        .isEqualTo(7);

    gate.open.countDown();
    for (int i = 0; i < 10; i++) {
      engine.client().handle("gate-" + i, ThroughTheGate.class).result(Duration.ofSeconds(20));
    }
    assertThat(gate.mostInside.get()).isEqualTo(3);
  }

  private static DurastepEngine start(DurastepEngine.Builder builder) {
    DurastepEngine engine = builder.build();
    engine.start();
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

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
