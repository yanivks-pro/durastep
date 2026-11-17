package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

class LogSecrecyIT {

  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final String CARD = "SECRET-CARD-4111";
  private static final String TOKEN = "SECRET-TOKEN-9f3a";
  private static final String PIN = "SECRET-PIN-7731";

  interface Payments {
    String tokenize(String card);
  }

  static final AtomicInteger attempts = new AtomicInteger();
  static volatile boolean bugFixed;

  static final Payments PAYMENTS =
      card -> {
        if (attempts.incrementAndGet() == 1) {
          throw new IllegalStateException("payment gateway timed out");
        }
        return TOKEN;
      };

  static final class Checkout implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String card) {
      String token =
          ctx.activities(
                  Payments.class,
                  ActivityOptions.builder()
                      .retryPolicy(
                          RetryPolicy.builder().initialBackoff(Duration.ofMillis(100)).build())
                      .build())
              .tokenize(card);
      String pin = ctx.awaitSignal("pin", String.class);
      if (!bugFixed) {
        throw new IllegalStateException("a bug in version 1.4.1");
      }
      return token + "/" + pin;
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

  @Test
  void noWorkflowDataEverAppearsInTheLogsEvenAtTraceLevel() throws Exception {
    Logger durastep = (Logger) LoggerFactory.getLogger("io.github.yanivks_pro.durastep");
    Level before = durastep.getLevel();
    ByteArrayOutputStream logs = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(logs, true, StandardCharsets.UTF_8));
    durastep.setLevel(Level.TRACE);
    try (DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(Checkout.class, Checkout::new)
            .activities(Payments.class, PAYMENTS)
            .build()) {
      engine.start();
      engine.client().start(Checkout.class, "checkout-1", CARD);
      await().atMost(WAIT).until(() -> attempts.get() == 2);
      engine.client().signal("checkout-1", "pin", PIN);
      await().atMost(WAIT).until(() -> engine.client().handle("checkout-1").describe().blocked());
      bugFixed = true;
      engine.client().handle("checkout-1").retryNow("fix deployed");

      assertThat(engine.client().handle("checkout-1", Checkout.class).result(WAIT))
          .isEqualTo(TOKEN + "/" + PIN);
    } finally {
      durastep.setLevel(before);
      System.setErr(originalErr);
    }

    String log = logs.toString(StandardCharsets.UTF_8);
    assertThat(log)
        .contains("workflowId=checkout-1", "failed with java.lang.IllegalStateException; retrying")
        .contains("is BLOCKED")
        .doesNotContain(CARD, TOKEN, PIN, "SECRET");
  }
}
