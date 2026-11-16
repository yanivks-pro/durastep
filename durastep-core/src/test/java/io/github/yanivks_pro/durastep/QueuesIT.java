package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class QueuesIT {

  interface Render {
    String prepare(String id);

    String renderOnGpu(String id);
  }

  static final class RecordingRender implements Render {
    final String engine;
    final List<String> calls;

    RecordingRender(String engine, List<String> calls) {
      this.engine = engine;
      this.calls = calls;
    }

    @Override
    public String prepare(String id) {
      calls.add("prepare on " + engine);
      return id;
    }

    @Override
    public String renderOnGpu(String id) {
      calls.add("render on " + engine);
      return "rendered " + id + " on " + engine;
    }
  }

  static final class RenderWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String id) {
      String prepared = ctx.activities(Render.class).prepare(id);
      return ctx.activities(Render.class, ActivityOptions.builder().queue("gpu").build())
          .renderOnGpu(prepared);
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final List<String> calls = new CopyOnWriteArrayList<>();
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
  void aGpuActivityRunsOnlyOnTheEngineThatServesTheGpuQueue() throws Exception {
    DurastepEngine cpu = start("cpu", "default");
    start("gpu", "gpu");

    for (int i = 0; i < 10; i++) {
      assertThat(
              cpu.client()
                  .start(RenderWorkflow.class, "job-" + i, "job-" + i)
                  .result(Duration.ofSeconds(20)))
          .isEqualTo("rendered job-" + i + " on gpu");
    }

    assertThat(calls).filteredOn(call -> call.startsWith("render")).containsOnly("render on gpu");
    assertThat(calls).filteredOn(call -> call.startsWith("prepare")).containsOnly("prepare on cpu");
  }

  @Test
  void aGpuActivityWaitsUntilAnEngineServesTheGpuQueue() throws Exception {
    DurastepEngine cpu = start("cpu", "default");
    cpu.client().start(RenderWorkflow.class, "job-late", "job-late");
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> count("durastep.tasks WHERE queue = 'gpu'") == 1);
    Thread.sleep(Duration.ofSeconds(2));
    assertThat(calls).containsExactly("prepare on cpu");

    start("gpu", "gpu");

    assertThat(cpu.client().handle("job-late", RenderWorkflow.class).result(Duration.ofSeconds(20)))
        .isEqualTo("rendered job-late on gpu");
  }

  private DurastepEngine start(String name, String queue) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .activityQueues(queue)
            .workflow(RenderWorkflow.class, RenderWorkflow::new)
            .activities(Render.class, new RecordingRender(name, calls))
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
