package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RetriesIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  static final class CardDeclinedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CardDeclinedException(String message) {
      super(message);
    }
  }

  interface Payments {
    String charge(String orderId);

    void releaseStock(String orderId);
  }

  static final class FlakyPayments implements Payments {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();
    volatile boolean decline;
    volatile long firstAttemptSleepMillis;
    volatile long everyAttemptSleepMillis;
    final List<Boolean> interrupted = new CopyOnWriteArrayList<>();

    @Override
    public String charge(String orderId) {
      calls.add("charge " + ActivityContext.current().idempotencyKey());
      sleep(calls.size() == 1 ? firstAttemptSleepMillis : 0);
      sleep(everyAttemptSleepMillis);
      if (decline) {
        throw new CardDeclinedException("card declined");
      }
      if (failuresLeft.getAndDecrement() > 0) {
        throw new IllegalStateException("payment API unavailable");
      }
      return "ch_" + orderId;
    }

    @Override
    public void releaseStock(String orderId) {
      calls.add("releaseStock " + ActivityContext.current().idempotencyKey());
    }

    private void sleep(long millis) {
      if (millis == 0) {
        return;
      }
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        interrupted.add(true);
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted", e);
      }
    }
  }

  static ActivityOptions options = ActivityOptions.DEFAULT;

  static final class PayWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      Payments payments = ctx.activities(Payments.class, options);
      try {
        return payments.charge(orderId);
      } catch (ActivityFailedException e) {
        if (e.failureType().equals(CardDeclinedException.class.getName())) {
          payments.releaseStock(orderId);
          return "declined after " + e.attempts() + " attempt";
        }
        throw e;
      }
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final FlakyPayments payments = new FlakyPayments();
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
  void aFlakyApiIsRetriedUntilItWorksWithTheSameIdempotencyKeyEveryTime() throws Exception {
    options = quickRetries(5).build();
    payments.failuresLeft.set(2);

    String result =
        startEngine(Duration.ofSeconds(30))
            .client()
            .start(PayWorkflow.class, "order-1", "order-1")
            .result(WAIT);

    assertThat(result).isEqualTo("ch_order-1");
    assertThat(payments.calls)
        .containsExactly(
            "charge order-1/activity-2", "charge order-1/activity-2", "charge order-1/activity-2");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void aNonRetryableErrorFailsAtOnceAndTheWorkflowCanHandleIt() throws Exception {
    options =
        ActivityOptions.builder()
            .retryPolicy(
                RetryPolicy.builder()
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofMillis(20))
                    .nonRetryable(CardDeclinedException.class)
                    .build())
            .build();
    payments.decline = true;

    String result =
        startEngine(Duration.ofSeconds(30))
            .client()
            .start(PayWorkflow.class, "order-2", "order-2")
            .result(WAIT);

    assertThat(result).isEqualTo("declined after 1 attempt");
    assertThat(payments.calls)
        .containsExactly("charge order-2/activity-2", "releaseStock order-2/activity-4");
  }

  @Test
  void whenEveryAttemptFailsTheWorkflowFailsWithTheLastError() throws Exception {
    options = quickRetries(3).build();
    payments.failuresLeft.set(100);
    DurastepEngine engine = startEngine(Duration.ofSeconds(30));

    assertThatThrownBy(
            () -> engine.client().start(PayWorkflow.class, "order-3", "order-3").result(WAIT))
        .isInstanceOf(WorkflowFailedException.class)
        .hasMessageContaining("Payments.charge failed after 3 attempts")
        .hasMessageContaining("payment API unavailable");
    assertThat(payments.calls).hasSize(3);
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void anAttemptThatHangsIsStoppedAndTheNextAttemptSucceeds() throws Exception {
    options = quickRetries(3).attemptTimeout(Duration.ofMillis(500)).build();
    payments.firstAttemptSleepMillis = 60_000;

    String result =
        startEngine(Duration.ofSeconds(30))
            .client()
            .start(PayWorkflow.class, "order-4", "order-4")
            .result(WAIT);

    assertThat(result).isEqualTo("ch_order-4");
    assertThat(payments.calls).hasSize(2);
    assertThat(payments.interrupted).containsExactly(true);
  }

  @Test
  void theTotalTimeoutStopsRetriesBeforeAllAttemptsAreUsed() throws Exception {
    options =
        ActivityOptions.builder()
            .totalTimeout(Duration.ofSeconds(1))
            .retryPolicy(
                RetryPolicy.builder()
                    .maxAttempts(100)
                    .initialBackoff(Duration.ofMillis(300))
                    .maxBackoff(Duration.ofMillis(300))
                    .build())
            .build();
    payments.failuresLeft.set(1_000);
    DurastepEngine engine = startEngine(Duration.ofSeconds(30));

    assertThatThrownBy(
            () -> engine.client().start(PayWorkflow.class, "order-5", "order-5").result(WAIT))
        .isInstanceOf(WorkflowFailedException.class)
        .hasMessageContaining("payment API unavailable");
    assertThat(payments.calls.size()).isBetween(2, 8);
  }

  @Test
  void anAttemptLongerThanTheLeaseIsNotTakenOverByAnotherEngine() throws Exception {
    options = quickRetries(1).attemptTimeout(Duration.ofSeconds(10)).build();
    payments.everyAttemptSleepMillis = 2_500;
    DurastepEngine first = startEngine(Duration.ofSeconds(1));
    startEngine(Duration.ofSeconds(1));

    String result = first.client().start(PayWorkflow.class, "order-6", "order-6").result(WAIT);

    assertThat(result).isEqualTo("ch_order-6");
    assertThat(payments.calls).containsExactly("charge order-6/activity-2");
  }

  private static ActivityOptions.Builder quickRetries(int attempts) {
    return ActivityOptions.builder()
        .retryPolicy(
            RetryPolicy.builder()
                .maxAttempts(attempts)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(40))
                .build());
  }

  private DurastepEngine startEngine(Duration lease) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(lease)
            .workflow(PayWorkflow.class, PayWorkflow::new)
            .activities(Payments.class, payments)
            .build();
    engine.start();
    engines.add(engine);
    return engine;
  }
}
