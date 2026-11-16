package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SignalsIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  record Expense(String id, long cents, long approvalWindowMillis) {}

  record Decision(boolean approved, String by) {}

  interface Prepare {
    void prepare(String id);
  }

  static volatile CountDownLatch preparing = new CountDownLatch(0);

  static final class GatedPrepare implements Prepare {
    @Override
    public void prepare(String id) {
      try {
        if (!preparing.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  static final class ExpenseWorkflow implements Workflow<Expense, String> {
    @Override
    public String run(WorkflowContext ctx, Expense expense) {
      ctx.setStatus("preparing");
      ctx.activities(Prepare.class).prepare(expense.id());
      ctx.setStatus("waiting for a manager");
      Optional<Decision> decision =
          ctx.awaitSignal(
              "decision", Decision.class, Duration.ofMillis(expense.approvalWindowMillis()));
      if (decision.isEmpty()) {
        ctx.setStatus("escalated to a director");
        Decision director = ctx.awaitSignal("decision", Decision.class);
        ctx.setStatus("decided by " + director.by());
        return (director.approved() ? "approved" : "rejected") + " by " + director.by();
      }
      ctx.setStatus("decided by " + decision.get().by());
      return (decision.get().approved() ? "approved" : "rejected") + " by " + decision.get().by();
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
  void startEngine() throws SQLException {
    preparing = new CountDownLatch(0);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(ExpenseWorkflow.class, ExpenseWorkflow::new)
            .activities(Prepare.class, new GatedPrepare())
            .build();
    engine.start();
  }

  @AfterEach
  void stopEngine() {
    preparing.countDown();
    engine.close();
  }

  @Test
  void aManagerApprovesAndTheStatusShowsEachStage() throws Exception {
    WorkflowHandle<String> expense = start("exp-1", 60_000);
    await().atMost(WAIT).until(() -> "waiting for a manager".equals(expense.describe().status()));

    engine.client().signal("exp-1", "decision", new Decision(true, "ana"));

    assertThat(expense.result(WAIT)).isEqualTo("approved by ana");
    assertThat(expense.describe().status()).isEqualTo("decided by ana");
    assertThat(expense.describe().state()).isEqualTo(WorkflowState.COMPLETED);
  }

  @Test
  void aSignalSentBeforeTheWorkflowWaitsIsKeptUntilItDoes() throws Exception {
    preparing = new CountDownLatch(1);
    WorkflowHandle<String> expense = start("exp-2", 60_000);
    await().atMost(WAIT).until(() -> "preparing".equals(expense.describe().status()));

    engine.client().signal("exp-2", "decision", new Decision(false, "bob"));
    preparing.countDown();

    assertThat(expense.result(WAIT)).isEqualTo("rejected by bob");
  }

  @Test
  void whenNobodyDecidesInTimeItEscalatesAndWaitsForTheDirector() throws Exception {
    WorkflowHandle<String> expense = start("exp-3", 1_000);
    await().atMost(WAIT).until(() -> "escalated to a director".equals(expense.describe().status()));

    engine.client().signal("exp-3", "decision", new Decision(true, "director"));

    assertThat(expense.result(WAIT)).isEqualTo("approved by director");
  }

  @Test
  void repeatingASignalWithTheSameRequestIdDeliversItOnce() throws Exception {
    WorkflowHandle<String> expense = start("exp-4", 1_000);
    await().atMost(WAIT).until(() -> "waiting for a manager".equals(expense.describe().status()));
    preparing = new CountDownLatch(0);

    boolean first =
        engine.client().signal("exp-4", "decision", new Decision(false, "ana"), "retry-1");
    boolean second =
        engine.client().signal("exp-4", "decision", new Decision(true, "ana"), "retry-1");

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(expense.result(WAIT)).isEqualTo("rejected by ana");
  }

  @Test
  void aFinishedWorkflowCannotBeSignalled() throws Exception {
    WorkflowHandle<String> expense = start("exp-5", 60_000);
    await().atMost(WAIT).until(() -> "waiting for a manager".equals(expense.describe().status()));
    engine.client().signal("exp-5", "decision", new Decision(true, "ana"));
    expense.result(WAIT);

    assertThatThrownBy(
            () -> engine.client().signal("exp-5", "decision", new Decision(true, "again")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMPLETED");
    assertThatThrownBy(() -> engine.client().signal("nope", "decision", new Decision(true, "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private WorkflowHandle<String> start(String id, long approvalWindowMillis) {
    return engine
        .client()
        .start(ExpenseWorkflow.class, id, new Expense(id, 4_200, approvalWindowMillis));
  }
}
