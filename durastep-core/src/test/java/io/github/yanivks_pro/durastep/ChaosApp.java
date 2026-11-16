package io.github.yanivks_pro.durastep;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

public final class ChaosApp {

  public static final Duration LEASE = Duration.ofSeconds(2);

  static final ActivityOptions OPTIONS =
      ActivityOptions.builder()
          .attemptTimeout(Duration.ofSeconds(10))
          .heartbeatTimeout(Duration.ofSeconds(2))
          .retryPolicy(
              RetryPolicy.builder()
                  .maxAttempts(20)
                  .initialBackoff(Duration.ofMillis(100))
                  .maxBackoff(Duration.ofSeconds(1))
                  .build())
          .build();

  public interface Shop {
    String charge(String orderId);

    void reserve(String orderId);

    void email(String orderId);
  }

  public static final class OrderWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      Shop shop = ctx.activities(Shop.class, OPTIONS);
      String chargeId = shop.charge(orderId);
      ctx.sleep(Duration.ofMillis(ctx.random().nextInt(2_000)));
      shop.reserve(orderId);
      shop.email(orderId);
      return chargeId;
    }
  }

  static final class FlakyShop implements Shop {
    private final DataSource dataSource;

    FlakyShop(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public String charge(String orderId) {
      effect("charge", orderId);
      return "ch_" + orderId;
    }

    @Override
    public void reserve(String orderId) {
      effect("reserve", orderId);
    }

    @Override
    public void email(String orderId) {
      effect("email", orderId);
    }

    private void effect(String kind, String orderId) {
      String key = ActivityContext.current().idempotencyKey();
      ThreadLocalRandom random = ThreadLocalRandom.current();
      boolean injectedFailure = random.nextInt(10) == 0;
      execute(
          "INSERT INTO chaos.executions (kind, workflow_id, idempotency_key, injected_failure, pid)"
              + " VALUES (?, ?, ?, ?, ?)",
          kind,
          orderId,
          key,
          injectedFailure,
          ProcessHandle.current().pid());
      try {
        Thread.sleep(random.nextInt(300));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      ActivityContext.current().heartbeat();
      if (injectedFailure) {
        throw new IllegalStateException("injected failure in " + kind + " for " + orderId);
      }
      execute(
          "INSERT INTO chaos.effects (kind, workflow_id, idempotency_key) VALUES (?, ?, ?)"
              + " ON CONFLICT (idempotency_key) DO NOTHING",
          kind,
          orderId,
          key);
    }

    private void execute(String sql, Object... parameters) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < parameters.length; i++) {
          statement.setObject(i + 1, parameters[i]);
        }
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private ChaosApp() {}

  public static void main(String[] args) throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(args[0]);
    dataSource.setUser(args[1]);
    dataSource.setPassword(args[2]);
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(LEASE)
            .workflowConcurrency(20)
            .activityConcurrency(50)
            .shutdownGrace(Duration.ZERO)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .activities(Shop.class, new FlakyShop(dataSource))
            .build();
    engine.start();
    Thread.sleep(Duration.ofHours(1));
  }
}
