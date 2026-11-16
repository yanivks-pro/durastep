package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.TestPostgres;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JdbcWorkflowStoreIT {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Payload ORDER = new Payload("{\"id\":\"order-42\",\"cents\":4200}");
  private static final Payload CHARGE_ARGS = new Payload("[\"order-42\",4200]");

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final JdbcWorkflowStore store = new JdbcWorkflowStore(dataSource);

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
    execute("TRUNCATE durastep.workflows CASCADE");
  }

  @Test
  void startCreatesTheWorkflowItsFirstEventAndADecideTask() throws SQLException {
    StartResult result = store.start("order-42", "OrderWorkflow", ORDER);

    assertThat(result).isEqualTo(new StartResult(true, "OrderWorkflow"));
    assertThat(store.find("order-42").orElseThrow().state()).isEqualTo(WorkflowState.RUNNING);
    assertThat(eventTypes("order-42")).containsExactly("WORKFLOW_STARTED");
    assertThat(count("tasks WHERE kind = 'WORKFLOW'")).isEqualTo(1);
  }

  @Test
  void startingTheSameIdAgainReturnsTheExistingWorkflowAndChangesNothing() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);

    StartResult again = store.start("order-42", "OtherWorkflow", new Payload("{}"));

    assertThat(again).isEqualTo(new StartResult(false, "OrderWorkflow"));
    assertThat(count("workflows")).isEqualTo(1);
    assertThat(count("history_events")).isEqualTo(1);
    assertThat(count("tasks")).isEqualTo(1);
  }

  @Test
  void aClaimedTaskCannotBeClaimedAgainWhileItsLeaseLasts() {
    store.start("order-42", "OrderWorkflow", ORDER);

    ClaimedTask first = store.claim(TaskKind.WORKFLOW, "default", "worker-a", LEASE).orElseThrow();
    Optional<ClaimedTask> second = store.claim(TaskKind.WORKFLOW, "default", "worker-b", LEASE);

    assertThat(first.workflowId()).isEqualTo("order-42");
    assertThat(first.fencingToken()).isEqualTo(1);
    assertThat(second).isEmpty();
  }

  @Test
  void aTaskWhoseLeaseExpiredCanBeClaimedAgainWithAHigherToken() {
    store.start("order-42", "OrderWorkflow", ORDER);
    assertThat(store.claim(TaskKind.WORKFLOW, "default", "worker-a", Duration.ZERO)).isPresent();

    ClaimedTask takeover =
        store.claim(TaskKind.WORKFLOW, "default", "worker-b", LEASE).orElseThrow();

    assertThat(takeover.fencingToken()).isEqualTo(2);
    assertThat(takeover.owner()).isEqualTo("worker-b");
  }

  @Test
  void aDecisionThatSchedulesAnActivityCreatesItsEventAndTask() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    DecisionInput input = store.loadDecision(decide);

    store.commitDecision(
        decide,
        input,
        List.of(
            new ScheduleActivity(
                "OrderActivities.chargeCard", CHARGE_ARGS, ActivityOptions.DEFAULT)));

    assertThat(input.workflowType()).isEqualTo("OrderWorkflow");
    assertThat(input.history())
        .extracting(HistoryEntry::event)
        .first()
        .isInstanceOf(WorkflowStarted.class);
    assertThat(eventTypes("order-42")).containsExactly("WORKFLOW_STARTED", "ACTIVITY_SCHEDULED");
    assertThat(count("tasks WHERE kind = 'WORKFLOW'")).isZero();
    ClaimedTask activity = claim(TaskKind.ACTIVITY);
    assertThat(store.loadActivity(activity))
        .isEqualTo(
            new ActivityToRun(
                "order-42",
                2,
                "OrderActivities.chargeCard",
                CHARGE_ARGS,
                ActivityOptions.DEFAULT,
                1));
  }

  @Test
  void aCompletedActivityGoesToTheInboxAndWakesTheDecider() throws SQLException {
    ClaimedTask activity = workflowWaitingForChargeCard();

    store.completeActivity(activity, new Completed(new Payload("\"ch_9f2\"")));

    assertThat(count("inbox WHERE kind = 'ACTIVITY_COMPLETED'")).isEqualTo(1);
    assertThat(count("tasks WHERE kind = 'ACTIVITY'")).isZero();
    assertThat(count("tasks WHERE kind = 'WORKFLOW'")).isEqualTo(1);
  }

  @Test
  void theDeciderMovesTheInboxIntoHistoryKeepingTheTimeItArrived() throws SQLException {
    store.completeActivity(
        workflowWaitingForChargeCard(), new Completed(new Payload("\"ch_9f2\"")));
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    DecisionInput input = store.loadDecision(decide);
    HistoryEntry arrived = input.historyWithInbox().get(2);

    store.commitDecision(
        decide, input, List.of(new CompleteWorkflow(new Payload("{\"chargeId\":\"ch_9f2\"}"))));

    assertThat(arrived.seq()).isEqualTo(3);
    assertThat(arrived.event()).isEqualTo(new ActivityCompleted(2, new Payload("\"ch_9f2\"")));
    List<HistoryEntry> saved = store.loadDecision(fakeTaskFor("order-42")).history();
    assertThat(saved.get(2).recordedAt()).isEqualTo(arrived.recordedAt());
    assertThat(eventTypes("order-42"))
        .containsExactly(
            "WORKFLOW_STARTED", "ACTIVITY_SCHEDULED", "ACTIVITY_COMPLETED", "WORKFLOW_COMPLETED");
    assertThat(count("inbox")).isZero();
    assertThat(count("tasks")).isZero();
    StoredWorkflow workflow = store.find("order-42").orElseThrow();
    assertThat(workflow.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(workflow.result().json()).isEqualTo("{\"chargeId\": \"ch_9f2\"}");
    assertThat(count("workflows WHERE closed_at IS NOT NULL AND history_length = 4")).isEqualTo(1);
  }

  @Test
  void aFailedWorkflowKeepsItsFailure() {
    store.completeActivity(
        workflowWaitingForChargeCard(),
        new Failed(new Failure("CardDeclined", "card declined"), false));
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    DecisionInput input = store.loadDecision(decide);

    store.commitDecision(
        decide, input, List.of(new FailWorkflow(new Failure("CardDeclined", "card declined"))));

    StoredWorkflow workflow = store.find("order-42").orElseThrow();
    assertThat(workflow.state()).isEqualTo(WorkflowState.FAILED);
    assertThat(workflow.failure()).isEqualTo(new Failure("CardDeclined", "card declined"));
  }

  @Test
  void aDecisionWithAStaleTokenIsRejectedAndWritesNothing() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask frozen =
        store.claim(TaskKind.WORKFLOW, "default", "worker-a", Duration.ZERO).orElseThrow();
    DecisionInput input = store.loadDecision(frozen);
    assertThat(store.claim(TaskKind.WORKFLOW, "default", "worker-b", LEASE)).isPresent();

    assertThatThrownBy(
            () ->
                store.commitDecision(
                    frozen,
                    input,
                    List.of(
                        new ScheduleActivity(
                            "OrderActivities.chargeCard", CHARGE_ARGS, ActivityOptions.DEFAULT))))
        .isInstanceOf(StaleTaskException.class);

    assertThat(eventTypes("order-42")).containsExactly("WORKFLOW_STARTED");
    assertThat(count("tasks WHERE kind = 'ACTIVITY'")).isZero();
  }

  @Test
  void anActivityResultWithAStaleTokenIsRejected() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    store.commitDecision(
        decide,
        store.loadDecision(decide),
        List.of(
            new ScheduleActivity(
                "OrderActivities.chargeCard", CHARGE_ARGS, ActivityOptions.DEFAULT)));
    ClaimedTask frozen =
        store.claim(TaskKind.ACTIVITY, "default", "worker-a", Duration.ZERO).orElseThrow();
    ClaimedTask takeover =
        store.claim(TaskKind.ACTIVITY, "default", "worker-b", LEASE).orElseThrow();
    store.completeActivity(takeover, new Completed(new Payload("\"ch_new\"")));

    assertThatThrownBy(
            () -> store.completeActivity(frozen, new Completed(new Payload("\"ch_old\""))))
        .isInstanceOf(StaleTaskException.class);

    assertThat(count("inbox")).isEqualTo(1);
    assertThat(store.loadDecision(claim(TaskKind.WORKFLOW)).inbox())
        .extracting(InboxEntry::event)
        .containsExactly(new ActivityCompleted(2, new Payload("\"ch_new\"")));
  }

  @Test
  void aResultArrivingDuringADecisionMakesTheDecideTaskRunAgain() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    DecisionInput input = store.loadDecision(decide);
    execute(
        "INSERT INTO durastep.tasks (workflow_id, kind, activity_seq) VALUES ('order-42', 'ACTIVITY', 9)");
    store.completeActivity(claim(TaskKind.ACTIVITY), new Completed(new Payload("\"late\"")));

    store.commitDecision(decide, input, List.of());

    assertThat(
            count("tasks WHERE kind = 'WORKFLOW' AND needs_rerun = false AND lease_owner IS NULL"))
        .isEqualTo(1);
    assertThat(count("inbox")).isEqualTo(1);
    ClaimedTask again = claim(TaskKind.WORKFLOW);
    assertThat(store.loadDecision(again).inbox()).hasSize(1);
  }

  @Test
  void aRetryPutsTheTaskBackLaterWithTheNextAttemptNumberAndTheError() throws SQLException {
    ClaimedTask first = workflowWaitingForChargeCard();
    Failure timeout = new Failure("java.net.SocketTimeoutException", "read timed out");

    boolean retried = store.retryActivity(first, timeout, Duration.ofSeconds(30), Optional.empty());

    assertThat(retried).isTrue();
    assertThat(count("tasks WHERE kind = 'ACTIVITY' AND attempt = 2 AND lease_owner IS NULL"))
        .isEqualTo(1);
    assertThat(count("tasks WHERE last_error->>'message' = 'read timed out'")).isEqualTo(1);
    assertThat(count("tasks WHERE visible_at > now() + interval '25 seconds'")).isEqualTo(1);
    assertThat(store.claim(TaskKind.ACTIVITY, "default", "worker-b", LEASE)).isEmpty();
    assertThat(count("inbox")).isZero();
  }

  @Test
  void aRetriedTaskIsClaimedAgainAsTheNextAttemptOnceItsDelayHasPassed() throws SQLException {
    ClaimedTask first = workflowWaitingForChargeCard();
    store.retryActivity(first, new Failure("E", "m"), Duration.ofMillis(1), Optional.empty());

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> store.claim(TaskKind.ACTIVITY, "default", "worker-b", LEASE).isPresent());

    assertThat(count("tasks WHERE kind = 'ACTIVITY' AND attempt = 2 AND lease_owner = 'worker-b'"))
        .isEqualTo(1);
  }

  @Test
  void aRetryThatWouldEndAfterTheTotalTimeoutBecomesTheFinalFailure() throws SQLException {
    ClaimedTask first = workflowWaitingForChargeCard();
    Failure error = new Failure("java.net.SocketTimeoutException", "read timed out");

    boolean retried =
        store.retryActivity(
            first, error, Duration.ofMinutes(10), Optional.of(Duration.ofMinutes(5)));

    assertThat(retried).isFalse();
    assertThat(count("tasks WHERE kind = 'ACTIVITY'")).isZero();
    assertThat(store.loadDecision(claim(TaskKind.WORKFLOW)).inbox())
        .extracting(InboxEntry::event)
        .containsExactly(new ActivityFailed(2, error, 1));
  }

  @Test
  void aFinalFailureRecordsHowManyAttemptsWereMade() {
    ClaimedTask first = workflowWaitingForChargeCard();
    store.retryActivity(first, new Failure("E", "1"), Duration.ofMillis(1), Optional.empty());
    ClaimedTask second =
        await()
            .atMost(Duration.ofSeconds(5))
            .until(
                () -> store.claim(TaskKind.ACTIVITY, "default", "worker-a", LEASE),
                Optional::isPresent)
            .orElseThrow();

    store.completeActivity(second, new Failed(new Failure("E", "2"), false));

    assertThat(second.attempt()).isEqualTo(2);
    assertThat(store.loadDecision(claim(TaskKind.WORKFLOW)).inbox())
        .extracting(InboxEntry::event)
        .containsExactly(new ActivityFailed(2, new Failure("E", "2"), 2));
  }

  @Test
  void anExtendedLeaseKeepsOtherWorkersAway() {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask task =
        store.claim(TaskKind.WORKFLOW, "default", "worker-a", Duration.ZERO).orElseThrow();

    store.extendLease(task, Duration.ofMinutes(1));

    assertThat(store.claim(TaskKind.WORKFLOW, "default", "worker-b", LEASE)).isEmpty();
  }

  @Test
  void aWorkerThatLostItsTaskCannotExtendItsLease() {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask frozen =
        store.claim(TaskKind.WORKFLOW, "default", "worker-a", Duration.ZERO).orElseThrow();
    assertThat(store.claim(TaskKind.WORKFLOW, "default", "worker-b", LEASE)).isPresent();

    assertThatThrownBy(() -> store.extendLease(frozen, Duration.ofMinutes(1)))
        .isInstanceOf(StaleTaskException.class);
  }

  @Test
  void aSleepSavesItsEventAndATimerThatFiresInTheFuture() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);

    store.commitDecision(
        decide, store.loadDecision(decide), List.of(new StartTimer(Duration.ofDays(3))));

    assertThat(eventTypes("order-42")).containsExactly("WORKFLOW_STARTED", "TIMER_STARTED");
    assertThat(
            count(
                "timers WHERE workflow_id = 'order-42' AND seq = 2"
                    + " AND fire_at BETWEEN now() + interval '3 days' - interval '1 minute'"
                    + " AND now() + interval '3 days' + interval '1 minute'"))
        .isEqualTo(1);
    assertThat(store.fireDueTimers(100)).isZero();
    assertThat(count("tasks")).isZero();
  }

  @Test
  void aDueTimerFiresOnceIntoTheInboxAndWakesTheDecider() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    store.commitDecision(
        decide, store.loadDecision(decide), List.of(new StartTimer(Duration.ofDays(3))));
    execute("UPDATE durastep.timers SET fire_at = now() - interval '1 second'");

    int firstPass = store.fireDueTimers(100);
    int secondPass = store.fireDueTimers(100);

    assertThat(firstPass).isEqualTo(1);
    assertThat(secondPass).isZero();
    assertThat(count("timers")).isZero();
    assertThat(store.loadDecision(claim(TaskKind.WORKFLOW)).inbox())
        .extracting(InboxEntry::event)
        .containsExactly(new TimerFired(2));
  }

  @Test
  void finishingAWorkflowRemovesItsTimers() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    store.commitDecision(
        decide, store.loadDecision(decide), List.of(new StartTimer(Duration.ofDays(3))));
    execute("INSERT INTO durastep.tasks (workflow_id, kind) VALUES ('order-42', 'WORKFLOW')");
    ClaimedTask finish = claim(TaskKind.WORKFLOW);

    store.commitDecision(
        finish, store.loadDecision(finish), List.of(new FailWorkflow(new Failure("E", "stopped"))));

    assertThat(count("timers")).isZero();
  }

  @Test
  void startingWithATimeoutStoresTheDeadlineAndItsTimer() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER, Optional.of(Duration.ofHours(1)));

    assertThat(
            count(
                "workflows WHERE deadline_at BETWEEN now() + interval '59 minutes'"
                    + " AND now() + interval '61 minutes'"))
        .isEqualTo(1);
    assertThat(
            count(
                "timers t JOIN durastep.workflows w ON w.id = t.workflow_id"
                    + " WHERE t.seq = 0 AND t.fire_at = w.deadline_at"))
        .isEqualTo(1);
  }

  @Test
  void startingAgainWithADifferentTimeoutChangesNothing() throws SQLException {
    store.start("order-42", "OrderWorkflow", ORDER, Optional.of(Duration.ofHours(1)));

    store.start("order-42", "OrderWorkflow", ORDER, Optional.of(Duration.ofSeconds(1)));

    assertThat(count("timers")).isEqualTo(1);
    assertThat(count("workflows WHERE deadline_at > now() + interval '59 minutes'")).isEqualTo(1);
  }

  @Test
  void rejectsASchemaNameThatIsNotAPlainIdentifier() {
    assertThatThrownBy(() -> new JdbcWorkflowStore(dataSource, "durastep; DROP TABLE x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ClaimedTask workflowWaitingForChargeCard() {
    store.start("order-42", "OrderWorkflow", ORDER);
    ClaimedTask decide = claim(TaskKind.WORKFLOW);
    store.commitDecision(
        decide,
        store.loadDecision(decide),
        List.of(
            new ScheduleActivity(
                "OrderActivities.chargeCard", CHARGE_ARGS, ActivityOptions.DEFAULT)));
    return claim(TaskKind.ACTIVITY);
  }

  private ClaimedTask claim(TaskKind kind) {
    return store.claim(kind, "default", "worker-a", LEASE).orElseThrow();
  }

  private static ClaimedTask fakeTaskFor(String workflowId) {
    return new ClaimedTask(0, workflowId, TaskKind.WORKFLOW, null, 1, 0, "reader");
  }

  private static List<String> eventTypes(String workflowId) throws SQLException {
    List<String> types = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT event_type FROM durastep.history_events WHERE workflow_id = '"
                    + workflowId
                    + "' ORDER BY seq")) {
      while (row.next()) {
        types.add(row.getString(1));
      }
    }
    return types;
  }

  private static int count(String tableAndCondition) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery("SELECT count(*) FROM durastep." + tableAndCondition)) {
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
