package io.github.yanivks_pro.durastep;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

public final class ShopApp {

  public static final String HANG_IN_RESERVE_STOCK = "hang-in-reserve-stock";
  public static final String RUN_TO_COMPLETION = "run-to-completion";
  public static final Duration LEASE = Duration.ofSeconds(2);
  public static final ActivityOptions ACTIVITY_OPTIONS =
      ActivityOptions.builder().attemptTimeout(Duration.ofSeconds(5)).build();

  public record Order(String id, long cents) {}

  public interface ShopActivities {
    String chargeCard(String orderId, long cents);

    void reserveStock(String orderId);

    void sendEmail(String orderId);
  }

  public static final class OrderWorkflow implements Workflow<Order, String> {
    @Override
    public String run(WorkflowContext ctx, Order order) {
      ShopActivities shop = ctx.activities(ShopActivities.class, ACTIVITY_OPTIONS);
      String chargeId = shop.chargeCard(order.id(), order.cents());
      shop.reserveStock(order.id());
      shop.sendEmail(order.id());
      return chargeId;
    }
  }

  static final class DatabaseShop implements ShopActivities {
    private final DataSource dataSource;
    private final boolean hangInReserveStock;

    DatabaseShop(DataSource dataSource, boolean hangInReserveStock) {
      this.dataSource = dataSource;
      this.hangInReserveStock = hangInReserveStock;
    }

    @Override
    public String chargeCard(String orderId, long cents) {
      String key = record("chargeCard");
      execute(
          "INSERT INTO shop.charges (idempotency_key, cents) VALUES (?, ?)"
              + " ON CONFLICT (idempotency_key) DO NOTHING",
          key,
          cents);
      return "ch_" + orderId;
    }

    @Override
    public void reserveStock(String orderId) {
      record("reserveStock");
      if (hangInReserveStock) {
        try {
          Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    }

    @Override
    public void sendEmail(String orderId) {
      record("sendEmail");
    }

    private String record(String activity) {
      String key = ActivityContext.current().idempotencyKey();
      execute(
          "INSERT INTO shop.calls (activity, idempotency_key, pid) VALUES (?, ?, ?)",
          activity,
          key,
          ProcessHandle.current().pid());
      return key;
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

  private ShopApp() {}

  public static void main(String[] args) throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(args[0]);
    dataSource.setUser(args[1]);
    dataSource.setPassword(args[2]);
    boolean hang = HANG_IN_RESERVE_STOCK.equals(args[3]);

    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(LEASE)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .activities(ShopActivities.class, new DatabaseShop(dataSource, hang))
            .build();
    engine.start();
    String chargeId =
        engine
            .client()
            .start(OrderWorkflow.class, "order-42", new Order("order-42", 4200))
            .result(Duration.ofMinutes(2));
    System.out.println("order-42 completed with " + chargeId);
    engine.close();
  }
}
