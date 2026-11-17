package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.HistoryRecord;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class VersioningIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Shop {
    void email(String order);

    void sms(String order);
  }

  static final List<String> sent = new CopyOnWriteArrayList<>();
  static volatile CountDownLatch emailGate = new CountDownLatch(0);
  static volatile CountDownLatch emailStarted = new CountDownLatch(1);

  static final Shop SHOP =
      new Shop() {
        @Override
        public void email(String order) {
          emailStarted.countDown();
          try {
            if (!emailGate.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("the email gate never opened");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending the email", e);
          }
          sent.add("email " + order);
        }

        @Override
        public void sms(String order) {
          sent.add("sms " + order);
        }
      };

  static final class Before {
    static final class Shipping implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        ctx.awaitSignal("shipped", String.class);
        ctx.activities(Shop.class).email(order);
        return "told by email";
      }
    }
  }

  static final class After {
    static final class Shipping implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        ctx.awaitSignal("shipped", String.class);
        if (ctx.version("send-sms-instead-of-email", 1) >= 1) {
          ctx.activities(Shop.class).sms(order);
          return "told by sms";
        }
        ctx.activities(Shop.class).email(order);
        return "told by email";
      }
    }
  }

  static final class AfterWithoutVersioning {
    static final class Shipping implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        ctx.awaitSignal("shipped", String.class);
        ctx.activities(Shop.class).sms(order);
        return "told by sms";
      }
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

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
    sent.clear();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
  }

  @Test
  void afterADeployOnlyWorkflowsThatWentPastTheChangeKeepTheOldPath() throws Exception {
    deployWhileOrder1IsSendingItsEmail("order-1", "order-2");

    try (DurastepEngine after = engine(After.Shipping.class, After.Shipping::new)) {
      after.client().start(After.Shipping.class, "order-3", "order-3");
      after.client().signal("order-2", "shipped", "today");
      after.client().signal("order-3", "shipped", "today");

      assertThat(result(after, "order-1")).isEqualTo("told by email");
      assertThat(result(after, "order-2")).isEqualTo("told by sms");
      assertThat(result(after, "order-3")).isEqualTo("told by sms");
      assertThat(types(after, "order-1")).doesNotContain("VERSION_MARKER");
      assertThat(types(after, "order-2")).contains("VERSION_MARKER");
      assertThat(types(after, "order-3")).contains("VERSION_MARKER");
    }
    assertThat(sent).containsExactlyInAnyOrder("email order-1", "sms order-2", "sms order-3");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void withoutAVersionCheckTheSameDeployBlocksTheWorkflowThatWentPastTheChange() throws Exception {
    deployWhileOrder1IsSendingItsEmail("order-4", "order-5");

    try (DurastepEngine after =
        engine(AfterWithoutVersioning.Shipping.class, AfterWithoutVersioning.Shipping::new)) {
      await().atMost(WAIT).until(() -> after.client().handle("order-4").describe().blocked());
      assertThat(after.client().handle("order-4").blockedError().orElseThrow())
          .contains("called Shop.sms at step 1", "Shop.email");
    }
  }

  private static void deployWhileOrder1IsSendingItsEmail(String passedTheChange, String beforeIt)
      throws Exception {
    emailGate = new CountDownLatch(1);
    emailStarted = new CountDownLatch(1);
    DurastepEngine before =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .shutdownGrace(Duration.ZERO)
            .workflow(Before.Shipping.class, Before.Shipping::new)
            .activities(Shop.class, SHOP)
            .build();
    before.start();
    before.client().start(Before.Shipping.class, passedTheChange, passedTheChange);
    before.client().start(Before.Shipping.class, beforeIt, beforeIt);
    before.client().signal(passedTheChange, "shipped", "today");
    assertThat(emailStarted.await(10, TimeUnit.SECONDS)).isTrue();
    before.close();
    emailGate.countDown();
  }

  private static String result(DurastepEngine engine, String workflowId) throws Exception {
    return engine.client().handle(workflowId, After.Shipping.class).result(WAIT);
  }

  private static List<String> types(DurastepEngine engine, String workflowId) {
    return engine.client().handle(workflowId).history().stream().map(HistoryRecord::type).toList();
  }

  private static <W extends Workflow<String, String>> DurastepEngine engine(
      Class<W> workflow, Supplier<W> factory) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(workflow, factory)
            .activities(Shop.class, SHOP)
            .build();
    engine.start();
    return engine;
  }
}
