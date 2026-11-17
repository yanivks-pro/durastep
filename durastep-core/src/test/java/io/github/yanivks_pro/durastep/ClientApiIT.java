package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowQuery;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ClientApiIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  static final class Echo implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return input;
    }
  }

  static final class Greeter implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String greeting) {
      return greeting + " " + ctx.awaitSignal("name", String.class);
    }
  }

  static final class Refuses implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      throw new WorkflowFailedException("refused " + input);
    }
  }

  static final class Broken implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      throw new IllegalStateException("a bug");
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
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE durastep.workflows CASCADE");
    }
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  void aClientWithoutWorkersStartsWorkflowsThatAnEngineRunsLater() throws Exception {
    DurastepClient client = DurastepClient.connect(dataSource);

    WorkflowHandle<String> echo = client.start(Echo.class, "echo-1", "hello");
    Thread.sleep(Duration.ofMillis(500));
    assertThat(echo.describe().state()).isEqualTo(WorkflowState.RUNNING);

    startEngine();
    assertThat(echo.result(WAIT)).isEqualTo("hello");
  }

  @Test
  void listFiltersByStateTypeAndBlockedAndReturnsTheNewestFirst() throws Exception {
    startEngine();
    DurastepClient client = engine.client();
    client.start(Echo.class, "echo-1", "one").result(WAIT);
    client.start(Echo.class, "echo-2", "two").result(WAIT);
    client.start(Greeter.class, "greeter-1", "hello");
    client.start(Broken.class, "broken-1", "x");
    await().atMost(WAIT).until(() -> client.handle("broken-1", Broken.class).describe().blocked());

    assertThat(client.list(WorkflowQuery.all()))
        .extracting(WorkflowDescription::id)
        .containsExactly("broken-1", "greeter-1", "echo-2", "echo-1");
    assertThat(client.list(WorkflowQuery.all().state(WorkflowState.COMPLETED)))
        .extracting(WorkflowDescription::id)
        .containsExactly("echo-2", "echo-1");
    assertThat(client.list(WorkflowQuery.all().type(Greeter.class)))
        .extracting(WorkflowDescription::id)
        .containsExactly("greeter-1");
    assertThat(client.list(WorkflowQuery.all().blocked(true)))
        .extracting(WorkflowDescription::id)
        .containsExactly("broken-1");
    assertThat(
            client.list(WorkflowQuery.all().state(WorkflowState.RUNNING).blocked(false).limit(1)))
        .extracting(WorkflowDescription::id)
        .containsExactly("greeter-1");
  }

  @Test
  void aHandleCanSignalAndCancelItsWorkflow() throws Exception {
    startEngine();
    WorkflowHandle<String> greeter = engine.client().start(Greeter.class, "greeter-1", "hello");
    WorkflowHandle<String> other = engine.client().start(Greeter.class, "greeter-2", "hi");

    greeter.signal("name", "Ada");
    assertThat(other.cancel()).isTrue();

    assertThat(greeter.result(WAIT)).isEqualTo("hello Ada");
    assertThatThrownBy(() -> other.result(WAIT)).isInstanceOf(WorkflowCancelledException.class);
    assertThat(other.cancel()).isFalse();
  }

  @Test
  void resultAsyncCompletesWithTheResultOrTheFailure() throws Exception {
    startEngine();
    WorkflowHandle<String> greeter = engine.client().start(Greeter.class, "greeter-1", "hello");
    WorkflowHandle<String> refuses = engine.client().start(Refuses.class, "refuses-1", "x");

    CompletableFuture<String> greeting = greeter.resultAsync();
    CompletableFuture<String> refusal = refuses.resultAsync();
    Thread.sleep(Duration.ofMillis(1_500));
    assertThat(greeting).isNotDone();
    greeter.signal("name", "Ada");

    assertThat(greeting.get(20, TimeUnit.SECONDS)).isEqualTo("hello Ada");
    assertThatThrownBy(() -> refusal.get(20, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(WorkflowFailedException.class)
        .cause()
        .hasMessageContaining("refused x");
  }

  @Test
  void aDescriptionSaysWhenTheWorkflowStartedAndFinished() throws Exception {
    startEngine();
    Instant before = Instant.now().minusSeconds(5);
    WorkflowHandle<String> greeter = engine.client().start(Greeter.class, "greeter-1", "hello");

    WorkflowDescription running = greeter.describe();
    greeter.signal("name", "Ada");
    greeter.result(WAIT);
    WorkflowDescription finished = greeter.describe();

    assertThat(running.startedAt()).isAfter(before);
    assertThat(running.closedAt()).isNull();
    assertThat(finished.closedAt()).isAfterOrEqualTo(finished.startedAt());
  }

  private void startEngine() {
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(Echo.class, Echo::new)
            .workflow(Greeter.class, Greeter::new)
            .workflow(Refuses.class, Refuses::new)
            .workflow(Broken.class, Broken::new)
            .build();
    engine.start();
  }
}
