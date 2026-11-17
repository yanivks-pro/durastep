package io.github.yanivks_pro.durastep.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.HistoryRecord;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.micrometer.DurastepMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;
import org.testcontainers.postgresql.PostgreSQLContainer;

class StarterIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Payments {
    String charge(String order);
  }

  interface Mail {
    void confirm(String order);
  }

  @Component
  static final class PriceList {
    long priceOf(String order) {
      return order.length() * 100L;
    }
  }

  @ActivityImplementation
  static final class Shop implements Payments, Mail {
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch slowCharge = new CountDownLatch(0);
    static volatile CountDownLatch chargeStarted = new CountDownLatch(1);

    @Override
    public String charge(String order) {
      chargeStarted.countDown();
      try {
        slowCharge.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      calls.add("charge " + order);
      return "ch_" + order;
    }

    @Override
    public void confirm(String order) {
      calls.add("confirm " + order);
    }
  }

  @WorkflowDefinition
  static final class OrderWorkflow implements Workflow<String, String> {
    private final PriceList prices;

    OrderWorkflow(PriceList prices) {
      this.prices = prices;
    }

    @Override
    public String run(WorkflowContext ctx, String order) {
      String charge = ctx.activities(Payments.class).charge(order);
      ctx.activities(Mail.class).confirm(order);
      return charge + " for " + prices.priceOf(order) + " cents";
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final ApplicationContextRunner app =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DurastepAutoConfiguration.class))
          .withBean(DataSource.class, () -> dataSource)
          .withUserConfiguration(PriceList.class, Shop.class, OrderWorkflow.class);

  @BeforeAll
  static void startPostgres() {
    postgres = new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:16-alpine"));
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try (DurastepEngine schemaOnly = DurastepEngine.builder().dataSource(dataSource).build()) {
      schemaOnly.start();
    }
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @BeforeEach
  void emptyTables() throws SQLException {
    Shop.calls.clear();
    Shop.slowCharge = new CountDownLatch(0);
    Shop.chargeStarted = new CountDownLatch(1);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
  }

  @Test
  void annotatedBeansRunAsWorkflowsAndActivitiesWithTheirDependencies() {
    app.run(
        context -> {
          DurastepClient client = context.getBean(DurastepClient.class);

          String result = client.start(OrderWorkflow.class, "order-1", "order-1").result(WAIT);

          assertThat(result).isEqualTo("ch_order-1 for 700 cents");
          assertThat(Shop.calls).containsExactly("charge order-1", "confirm order-1");
          assertThat(context).hasSingleBean(DurastepEngine.class);
        });
  }

  @Test
  void withWorkersDisabledThereIsOnlyAClient() {
    app.withPropertyValues("durastep.worker.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DurastepEngine.class);
              DurastepClient client = context.getBean(DurastepClient.class);
              client.start(OrderWorkflow.class, "order-2", "order-2");
              Thread.sleep(Duration.ofMillis(500));
              assertThat(client.handle("order-2").describe().state())
                  .isEqualTo(WorkflowState.RUNNING);
            });
  }

  @Test
  void withoutADataSourceNothingIsCreated() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DurastepAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(DurastepClient.class));
  }

  @Test
  void theHealthCheckReportsTheCountsAndOnlyExistsWithSpringBootHealth() {
    app.run(
        context -> {
          HealthIndicator health = context.getBean(DurastepHealthIndicator.class);
          assertThat(health.health().getStatus()).isEqualTo(Status.UP);
          assertThat(health.health().getDetails())
              .containsKeys("running", "blocked", "waitingDecisions", "oldestWaitingMillis");
        });
    app.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
        .run(context -> assertThat(context).doesNotHaveBean(DurastepHealthIndicator.class));
  }

  @Test
  void theHealthCheckIsDownWhenTheDatabaseCannotBeReached() {
    PGSimpleDataSource nowhere = new PGSimpleDataSource();
    nowhere.setUrl("jdbc:postgresql://localhost:1/nowhere");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DurastepAutoConfiguration.class))
        .withBean(DataSource.class, () -> nowhere)
        .withPropertyValues("durastep.worker.enabled=false")
        .run(
            context ->
                assertThat(context.getBean(DurastepHealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.DOWN));
  }

  @Test
  void metricsAreRecordedWhenThereIsAMeterRegistry() {
    app.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context -> {
              context
                  .getBean(DurastepClient.class)
                  .start(OrderWorkflow.class, "order-3", "order-3")
                  .result(WAIT);
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              assertThat(
                      registry
                          .get("durastep.workflows.started")
                          .tag("type", "OrderWorkflow")
                          .counter()
                          .count())
                  .isEqualTo(1);
              await()
                  .atMost(WAIT)
                  .until(() -> registry.find("durastep.workflows.running").gauge() != null);
            });
    app.withClassLoader(new FilteredClassLoader(DurastepMetrics.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DurastepMetrics.class);
              assertThat(
                      context
                          .getBean(DurastepClient.class)
                          .start(OrderWorkflow.class, "order-4", "order-4")
                          .result(WAIT))
                  .startsWith("ch_order-4");
            });
  }

  static final class RecordingListener implements EngineListener {
    final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void workflowStarted(String type) {
      events.add("started " + type);
    }

    @Override
    public void workflowFinished(String type, WorkflowState state) {
      events.add("finished " + type + " " + state);
    }

    @Override
    public void decisionMade(String type, Duration took) {
      events.add("decision " + type);
    }

    @Override
    public void activityFinished(String activity, ActivityOutcome outcome, Duration took) {
      events.add("activity " + activity + " " + outcome);
    }
  }

  @Test
  void anApplicationsOwnListenerAndTheMetricsBothHearEveryEvent() {
    app.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(RecordingListener.class, RecordingListener::new)
        .run(
            context -> {
              context
                  .getBean(DurastepClient.class)
                  .start(OrderWorkflow.class, "order-6", "order-6")
                  .result(WAIT);
              RecordingListener recorder = context.getBean(RecordingListener.class);
              MeterRegistry registry = context.getBean(MeterRegistry.class);

              assertThat(recorder.events)
                  .contains(
                      "started OrderWorkflow",
                      "activity Payments.charge SUCCEEDED",
                      "activity Mail.confirm SUCCEEDED",
                      "decision OrderWorkflow",
                      "finished OrderWorkflow COMPLETED");
              assertThat(
                      registry
                          .get("durastep.workflows.finished")
                          .tags("type", "OrderWorkflow", "state", "completed")
                          .counter()
                          .count())
                  .isEqualTo(1);
            });
  }

  @Test
  void invalidSettingsStopTheApplicationFromStarting() {
    app.withPropertyValues("durastep.workflow-concurrency=0")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("workflowConcurrency must be at least 1"));
    app.withPropertyValues("durastep.retention=soon")
        .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("soon"));
    app.withPropertyValues("durastep.retention=off", "durastep.max-payload-size=1MB")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void shuttingDownLetsAnActivityThatIsRunningFinishAndSaveItsResult() throws Exception {
    Shop.slowCharge = new CountDownLatch(1);
    app.run(
        context -> {
          context.getBean(DurastepClient.class).start(OrderWorkflow.class, "order-5", "order-5");
          assertThat(Shop.chargeStarted.await(10, TimeUnit.SECONDS)).isTrue();
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      Thread.sleep(Duration.ofMillis(500));
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    Shop.slowCharge.countDown();
                  });
        });

    assertThat(Shop.calls).contains("charge order-5");
    assertThat(DurastepClient.connect(dataSource).handle("order-5").history())
        .extracting(HistoryRecord::type)
        .contains("ACTIVITY_SCHEDULED");
    assertThat(
            DurastepClient.connect(dataSource).handle("order-5").history().stream()
                    .anyMatch(event -> event.type().equals("ACTIVITY_COMPLETED"))
                || inboxHasResultFor("order-5"))
        .isTrue();
  }

  private static boolean inboxHasResultFor(String workflowId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        java.sql.ResultSet row =
            statement.executeQuery(
                "SELECT count(*) FROM durastep.inbox WHERE workflow_id = '"
                    + workflowId
                    + "' AND kind = 'ACTIVITY_COMPLETED'")) {
      row.next();
      return row.getInt(1) == 1;
    }
  }
}
