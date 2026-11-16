package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class TimersIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  record Reminder(String id, Duration delay) {}

  record Times(Instant before, Instant after) {}

  interface Mailer {
    void send(String id);
  }

  static final class RecordingMailer implements Mailer {
    final Map<String, Instant> sentAt = new ConcurrentHashMap<>();

    @Override
    public void send(String id) {
      sentAt.put(ActivityContext.current().idempotencyKey(), Instant.now());
    }
  }

  static final class ReminderWorkflow implements Workflow<Reminder, Times> {
    @Override
    public Times run(WorkflowContext ctx, Reminder reminder) {
      Instant before = ctx.now();
      ctx.sleep(reminder.delay());
      Instant after = ctx.now();
      ctx.activities(Mailer.class).send(reminder.id());
      return new Times(before, after);
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final RecordingMailer mailer = new RecordingMailer();
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
  void aWorkflowSleepsForTheRequestedTimeAndThenCarriesOn() throws Exception {
    DurastepEngine engine = startEngine();
    long started = System.nanoTime();

    Times times =
        engine
            .client()
            .start(
                ReminderWorkflow.class, "remind-1", new Reminder("remind-1", Duration.ofSeconds(2)))
            .result(WAIT);
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertThat(Duration.between(times.before(), times.after()))
        .isBetween(Duration.ofSeconds(2), Duration.ofMillis(3_100));
    assertThat(took).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    assertThat(mailer.sentAt).containsOnlyKeys("remind-1/activity-4");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aTimerSurvivesTheEngineStoppingAndAnotherEngineFiresIt() throws Exception {
    DurastepEngine first = startEngine();
    first
        .client()
        .start(ReminderWorkflow.class, "remind-2", new Reminder("remind-2", Duration.ofSeconds(3)));
    await().atMost(WAIT).until(() -> count("durastep.timers") == 1);
    first.close();
    engines.remove(first);
    assertThat(mailer.sentAt).isEmpty();

    Thread.sleep(Duration.ofSeconds(4));
    assertThat(mailer.sentAt).isEmpty();
    assertThat(count("durastep.timers")).isEqualTo(1);

    DurastepEngine second = startEngine();
    second.client().handle("remind-2", ReminderWorkflow.class).result(WAIT);

    assertThat(mailer.sentAt).containsOnlyKeys("remind-2/activity-4");
    assertThat(count("durastep.timers")).isZero();
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aSleepOfThreeDaysIsStoredAndResumesWhenItsTimeComes() throws Exception {
    DurastepEngine engine = startEngine();
    engine
        .client()
        .start(ReminderWorkflow.class, "remind-3", new Reminder("remind-3", Duration.ofDays(3)));
    await().atMost(WAIT).until(() -> count("durastep.timers") == 1);
    assertThat(
            count(
                "durastep.timers WHERE fire_at > now() + interval '2 days 23 hours'"
                    + " AND fire_at < now() + interval '3 days 1 hour'"))
        .isEqualTo(1);
    assertThat(mailer.sentAt).isEmpty();

    execute("UPDATE durastep.timers SET fire_at = now()");

    Times times = engine.client().handle("remind-3", ReminderWorkflow.class).result(WAIT);
    assertThat(mailer.sentAt).containsOnlyKeys("remind-3/activity-4");
    assertThat(times.after()).isAfter(times.before());
  }

  @Test
  void manySleepingWorkflowsAllWakeUpOnce() throws Exception {
    DurastepEngine engine = startEngine();
    startEngine();
    for (int i = 0; i < 20; i++) {
      engine
          .client()
          .start(
              ReminderWorkflow.class,
              "many-" + i,
              new Reminder("many-" + i, Duration.ofSeconds(1)));
    }

    for (int i = 0; i < 20; i++) {
      engine.client().handle("many-" + i, ReminderWorkflow.class).result(WAIT);
    }

    assertThat(mailer.sentAt).hasSize(20);
    assertThat(count("durastep.history_events WHERE event_type = 'TIMER_FIRED'")).isEqualTo(20);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private DurastepEngine startEngine() {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(ReminderWorkflow.class, ReminderWorkflow::new)
            .activities(Mailer.class, mailer)
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

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
