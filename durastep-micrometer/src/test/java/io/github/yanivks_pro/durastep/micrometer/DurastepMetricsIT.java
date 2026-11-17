package io.github.yanivks_pro.durastep.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DurastepMetricsIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Greetings {
    String greet(String name);
  }

  static final class Greeter implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      ctx.sleep(Duration.ofMillis(100));
      return ctx.activities(Greetings.class).greet(name);
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void startPostgres() {
    postgres = new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:16-alpine"));
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

  @Test
  void aRealEngineFillsTheCountersTimersAndGauges() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (DurastepMetrics metrics = DurastepMetrics.bindTo(registry);
        DurastepEngine engine =
            DurastepEngine.builder()
                .dataSource(dataSource)
                .listener(metrics)
                .workflow(Greeter.class, Greeter::new)
                .activities(Greetings.class, name -> "hello " + name)
                .build()) {
      engine.start();
      metrics.watch(engine.client(), Duration.ofMillis(200));

      assertThat(engine.client().start(Greeter.class, "greeter-1", "Ada").result(WAIT))
          .isEqualTo("hello Ada");
      DurastepClient client = DurastepClient.connect(dataSource);
      client.start(Greeter.class, "waiting-1", "Bob");
      engine.stop();
      execute("UPDATE durastep.workflows SET blocked = true WHERE id = 'waiting-1'");

      await()
          .atMost(WAIT)
          .untilAsserted(
              () -> {
                assertThat(registry.get("durastep.workflows.running").gauge().value()).isEqualTo(1);
                assertThat(registry.get("durastep.workflows.blocked").gauge().value()).isEqualTo(1);
                assertThat(
                        registry
                            .get("durastep.tasks.ready")
                            .tags("kind", "decision", "queue", "default")
                            .gauge()
                            .value())
                    .isEqualTo(1);
              });
      assertThat(
              registry.get("durastep.workflows.started").tag("type", "Greeter").counter().count())
          .isEqualTo(1);
      assertThat(
              registry
                  .get("durastep.workflows.finished")
                  .tags("type", "Greeter", "state", "completed")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(
              registry
                  .get("durastep.activities")
                  .tags("activity", "Greetings.greet", "outcome", "succeeded")
                  .timer()
                  .count())
          .isEqualTo(1);
      assertThat(registry.get("durastep.decisions").tag("type", "Greeter").timer().count())
          .isGreaterThanOrEqualTo(2);
      assertThat(registry.get("durastep.timers.lateness").timer().count()).isEqualTo(1);
      assertThat(registry.get("durastep.tasks.oldest.age").gauge().value()).isNotNegative();
    }
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
