package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SecurityGuideIT {

  private static final Path GUIDE = Path.of("..", "docs", "guides", "security.md");
  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Greetings {
    String greet(String name);
  }

  static final class Greeter implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      ctx.sleep(Duration.ofMillis(100));
      return ctx.activities(Greetings.class).greet(name)
          + " "
          + ctx.awaitSignal("end", String.class);
    }
  }

  private static PostgreSQLContainer postgres;

  @BeforeAll
  static void startPostgres() throws Exception {
    postgres = TestPostgres.newContainer();
    postgres.start();
    try (Connection admin =
            dataSource(postgres.getUsername(), postgres.getPassword()).getConnection();
        Statement statement = admin.createStatement()) {
      for (String sql : Pattern.compile(";").splitAsStream(setupFromTheGuide()).toList()) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
      statement.execute("CREATE SCHEMA other");
      statement.execute("CREATE TABLE other.secrets (value text)");
    }
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void theGuidesSetupRunsWorkflowsAsAUserThatCanChangeNothingElse() throws Exception {
    PGSimpleDataSource owner = dataSource("durastep_owner", "change-me-owner");
    PGSimpleDataSource app = dataSource("durastep_app", "change-me-app");
    try (DurastepEngine migrations = DurastepEngine.builder().dataSource(owner).build()) {
      migrations.start();
    }

    try (DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(app)
            .applyMigrations(false)
            .workflow(Greeter.class, Greeter::new)
            .activities(Greetings.class, name -> "hello " + name)
            .build()) {
      engine.start();
      engine.client().start(Greeter.class, "greeter-1", "Ada");
      engine.client().signal("greeter-1", "end", "and goodbye");
      assertThat(engine.client().handle("greeter-1", Greeter.class).result(WAIT))
          .isEqualTo("hello Ada and goodbye");
      assertThat(engine.client().handle("greeter-1").retryNow("nothing to retry")).isFalse();
    }

    try (Connection connection = app.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("CREATE TABLE durastep.extra (id int)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> statement.execute("DROP TABLE durastep.history_events"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("must be owner");
      assertThatThrownBy(() -> statement.execute("SELECT * FROM other.secrets"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }
  }

  private static String setupFromTheGuide() throws Exception {
    String guide = Files.readString(GUIDE);
    int start = guide.indexOf("```sql\n") + "```sql\n".length();
    return guide.substring(start, guide.indexOf("```", start));
  }

  private static PGSimpleDataSource dataSource(String user, String password) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(user);
    dataSource.setPassword(password);
    return dataSource;
  }
}
