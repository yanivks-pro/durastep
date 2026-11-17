package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.TestPostgres;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class LockOrderIT {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Payload ORDER = new Payload("{\"id\":\"order-42\",\"cents\":4200}");

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final JdbcWorkflowStore store = new JdbcWorkflowStore(dataSource);

  static final class Pause implements CrashPoints {
    private final CrashPoint at;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    Pause(CrashPoint at) {
      this.at = at;
    }

    @Override
    public void reached(CrashPoint point) {
      if (point != at) {
        return;
      }
      reached.countDown();
      try {
        if (!released.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    void awaitReached() throws InterruptedException {
      assertThat(reached.await(10, TimeUnit.SECONDS)).as("reached " + at).isTrue();
    }

    void release() {
      released.countDown();
    }
  }

  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("durastep")
        .locations("classpath:durastep/db/migration")
        .load()
        .migrate();
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

  @Test
  void anActivityResultSavedWhileItsWorkflowFinishesDoesNotDeadlock() throws Exception {
    store.start("order-1", "OrderWorkflow", ORDER);
    ClaimedTask first = claim(TaskKind.WORKFLOW);
    store.commitDecision(
        first,
        store.loadDecision(first),
        List.of(
            new ScheduleActivity(
                "OrderActivities.chargeCard",
                new Payload("[\"order-42\",4200]"),
                ActivityOptions.DEFAULT)));
    ClaimedTask activity = claim(TaskKind.ACTIVITY);
    ClaimedTask decide = decisionReadyToFinish("order-1");
    DecisionInput input = store.loadDecision(decide);
    Pause pause = new Pause(CrashPoint.MID_ACTIVITY_COMPLETE);
    JdbcWorkflowStore pausing = new JdbcWorkflowStore(dataSource, "durastep", pause);

    CompletableFuture<Void> saving =
        inTheBackground(
            () -> pausing.completeActivity(activity, new Completed(new Payload("\"ch_1\""))));
    pause.awaitReached();
    CompletableFuture<Void> finishing =
        inTheBackground(
            () ->
                store.commitDecision(
                    decide, input, List.of(new CompleteWorkflow(new Payload("1")))));
    await().atMost(WAIT).until(() -> finishing.isDone() || someoneWaitsForALock());
    pause.release();

    saving.get(20, TimeUnit.SECONDS);
    finishing.get(20, TimeUnit.SECONDS);
    assertThat(store.find("order-1").orElseThrow().state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(leftOver("order-1")).isZero();
  }

  @Test
  void aTimerFiredWhileItsWorkflowFinishesDoesNotDeadlock() throws Exception {
    store.start("order-2", "OrderWorkflow", ORDER);
    ClaimedTask first = claim(TaskKind.WORKFLOW);
    store.commitDecision(first, store.loadDecision(first), List.of(new StartTimer(Duration.ZERO)));
    ClaimedTask decide = decisionReadyToFinish("order-2");
    DecisionInput input = store.loadDecision(decide);
    Pause pause = new Pause(CrashPoint.MID_TIMER_FIRE);
    JdbcWorkflowStore pausing = new JdbcWorkflowStore(dataSource, "durastep", pause);

    CompletableFuture<Void> firing = inTheBackground(() -> pausing.fireDueTimers(10));
    pause.awaitReached();
    CompletableFuture<Void> finishing =
        inTheBackground(
            () ->
                store.commitDecision(
                    decide, input, List.of(new CompleteWorkflow(new Payload("1")))));
    await().atMost(WAIT).until(() -> finishing.isDone() || someoneWaitsForALock());
    pause.release();

    firing.get(20, TimeUnit.SECONDS);
    finishing.get(20, TimeUnit.SECONDS);
    assertThat(store.find("order-2").orElseThrow().state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(leftOver("order-2")).isZero();
  }

  private ClaimedTask decisionReadyToFinish(String workflowId) {
    store.signal(workflowId, "finish", new Payload("true"), "finish-" + workflowId);
    return claim(TaskKind.WORKFLOW);
  }

  private ClaimedTask claim(TaskKind kind) {
    return store.claim(kind, "default", "worker-a", LEASE).orElseThrow();
  }

  private static CompletableFuture<Void> inTheBackground(Runnable work) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                work.run();
                future.complete(null);
              } catch (RuntimeException e) {
                future.completeExceptionally(e);
              }
            });
    return future;
  }

  private static boolean someoneWaitsForALock() throws SQLException {
    return count("pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
        > 0;
  }

  private static int leftOver(String workflowId) throws SQLException {
    String where = " WHERE workflow_id = '" + workflowId + "'";
    return count("durastep.tasks" + where)
        + count("durastep.inbox" + where)
        + count("durastep.timers" + where);
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
