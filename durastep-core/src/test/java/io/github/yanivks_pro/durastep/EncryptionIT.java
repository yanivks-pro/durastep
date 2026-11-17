package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.AesGcmPayloadCodec;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.PayloadCodec;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class EncryptionIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final String CARD = "4111-SECRET-CARD";
  private static final String PIN = "SECRET-PIN-7731";

  interface Payments {
    String tokenize(String card);
  }

  static final List<String> seenByActivities = new CopyOnWriteArrayList<>();

  static final Payments PAYMENTS =
      card -> {
        seenByActivities.add(card);
        return "token for " + card;
      };

  static final class Checkout implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String card) {
      String token = ctx.activities(Payments.class).tokenize(card);
      String pin = ctx.awaitSignal("pin", String.class);
      return token + " confirmed with " + pin;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final SecretKey k1 = AesGcmPayloadCodec.newKey();
  private final SecretKey k2 = AesGcmPayloadCodec.newKey();

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
    seenByActivities.clear();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS durastep");
      statement.execute(
          "DO $$ BEGIN IF to_regclass('durastep.workflows') IS NOT NULL THEN"
              + " TRUNCATE durastep.workflows CASCADE; END IF; END $$");
    }
  }

  @Test
  void workflowsSeeTheirDataButTheDatabaseNeverHoldsItInPlainText() throws Exception {
    PayloadCodec codec = AesGcmPayloadCodec.withKeys(Map.of("k1", k1), "k1");
    try (DurastepEngine engine = engine(codec)) {
      engine.client().start(Checkout.class, "checkout-1", CARD);
      await().atMost(WAIT).until(() -> !seenByActivities.isEmpty());
      engine.client().signal("checkout-1", "pin", PIN);

      String result = engine.client().handle("checkout-1", Checkout.class).result(WAIT);

      assertThat(result).isEqualTo("token for " + CARD + " confirmed with " + PIN);
      assertThat(seenByActivities).containsExactly(CARD);
    }
    assertThat(storedText()).isNotEmpty().noneMatch(text -> text.contains("SECRET"));
    assertThat(storedText()).anyMatch(text -> text.contains("\"enc\": \"v1\""));
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  @Test
  void keysCanBeRotatedWhileWorkflowsAreRunning() throws Exception {
    try (DurastepEngine before = engine(AesGcmPayloadCodec.withKeys(Map.of("k1", k1), "k1"))) {
      before.client().start(Checkout.class, "checkout-2", CARD);
      await().atMost(WAIT).until(() -> !seenByActivities.isEmpty());
    }

    PayloadCodec rotated = AesGcmPayloadCodec.withKeys(Map.of("k1", k1, "k2", k2), "k2");
    try (DurastepEngine after = engine(rotated)) {
      after.client().signal("checkout-2", "pin", PIN);

      assertThat(after.client().handle("checkout-2", Checkout.class).result(WAIT))
          .isEqualTo("token for " + CARD + " confirmed with " + PIN);
    }
    assertThat(storedText()).anyMatch(text -> text.contains("\"key\": \"k1\""));
    assertThat(storedText()).anyMatch(text -> text.contains("\"key\": \"k2\""));
    assertThat(storedText()).noneMatch(text -> text.contains("SECRET"));
  }

  @Test
  void aClientNeedsTheCodecToReadEncryptedResults() throws Exception {
    PayloadCodec codec = AesGcmPayloadCodec.withKeys(Map.of("k1", k1), "k1");
    try (DurastepEngine engine = engine(codec)) {
      engine.client().start(Checkout.class, "checkout-3", CARD);
      engine.client().signal("checkout-3", "pin", PIN);
      engine.client().handle("checkout-3", Checkout.class).result(WAIT);
    }

    DurastepClient withCodec = DurastepClient.connect(dataSource, "durastep", codec);
    DurastepClient withoutCodec = DurastepClient.connect(dataSource);

    assertThat(withCodec.handle("checkout-3", Checkout.class).result(WAIT)).contains(CARD);
    assertThatThrownBy(() -> withoutCodec.handle("checkout-3", Checkout.class).result(WAIT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("encrypted with key k1")
        .hasMessageContaining("PayloadCodec");
  }

  private static DurastepEngine engine(PayloadCodec codec) {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .payloadCodec(codec)
            .workflow(Checkout.class, Checkout::new)
            .activities(Payments.class, PAYMENTS)
            .build();
    engine.start();
    return engine;
  }

  private static List<String> storedText() throws SQLException {
    List<String> texts = new ArrayList<>();
    String[] queries = {
      "SELECT input::text FROM durastep.workflows WHERE input IS NOT NULL",
      "SELECT result::text FROM durastep.workflows WHERE result IS NOT NULL",
      "SELECT payload::text FROM durastep.history_events WHERE payload IS NOT NULL",
      "SELECT payload::text FROM durastep.inbox WHERE payload IS NOT NULL"
    };
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String query : queries) {
        try (ResultSet row = statement.executeQuery(query)) {
          while (row.next()) {
            texts.add(row.getString(1));
          }
        }
      }
    }
    return texts;
  }
}
