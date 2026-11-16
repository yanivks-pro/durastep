package io.github.yanivks_pro.durastep;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityLeaseLostException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

public final class ZombieApp {

  public interface Payments {
    String slowCharge(String orderId);
  }

  public static final class ChargeWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String orderId) {
      return ctx.activities(
              Payments.class,
              ActivityOptions.builder()
                  .attemptTimeout(Duration.ofMinutes(1))
                  .heartbeatTimeout(Duration.ofSeconds(2))
                  .retryPolicy(RetryPolicy.none())
                  .build())
          .slowCharge(orderId);
    }
  }

  static final class SlowPayments implements Payments {
    private final DataSource dataSource;

    SlowPayments(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public String slowCharge(String orderId) {
      String key = ActivityContext.current().idempotencyKey();
      record("started");
      try {
        for (int i = 0; i < 40; i++) {
          Thread.sleep(150);
          ActivityContext.current().heartbeat();
        }
      } catch (ActivityLeaseLostException e) {
        record("lease-lost");
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      execute(
          "INSERT INTO zombie.charges (idempotency_key) VALUES (?) ON CONFLICT DO NOTHING", key);
      record("done");
      return "charged " + orderId;
    }

    private void record(String what) {
      execute(
          "INSERT INTO zombie.calls (what, pid) VALUES (?, ?)",
          what,
          ProcessHandle.current().pid());
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

  private ZombieApp() {}

  public static void main(String[] args) throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(args[0]);
    dataSource.setUser(args[1]);
    dataSource.setPassword(args[2]);
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(Duration.ofSeconds(2))
            .workflow(ChargeWorkflow.class, ChargeWorkflow::new)
            .activities(Payments.class, new SlowPayments(dataSource))
            .build();
    engine.start();
    Thread.sleep(Duration.ofMinutes(10));
  }
}
