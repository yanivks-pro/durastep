package io.github.yanivks_pro.durastep;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Saga;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

public final class TripApp {

  public static final String HANG_WHILE_CANCELLING_HOTEL = "hang-while-cancelling-hotel";
  public static final String RUN_NORMALLY = "run-normally";

  static final class NoCarsLeftException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NoCarsLeftException(String message) {
      super(message);
    }
  }

  public interface Trips {
    String bookFlight(String trip);

    String bookHotel(String trip);

    void bookCar(String trip);

    void cancelFlight(String flight);

    void cancelHotel(String hotel);
  }

  public static final class TripWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String trip) {
      Trips trips =
          ctx.activities(
              Trips.class,
              ActivityOptions.builder()
                  .attemptTimeout(Duration.ofSeconds(5))
                  .retryPolicy(
                      RetryPolicy.builder().nonRetryable(NoCarsLeftException.class).build())
                  .build());
      Saga saga = ctx.saga();
      String flight = trips.bookFlight(trip);
      saga.addCompensation(() -> trips.cancelFlight(flight));
      String hotel = trips.bookHotel(trip);
      saga.addCompensation(() -> trips.cancelHotel(hotel));
      try {
        trips.bookCar(trip);
      } catch (ActivityFailedException e) {
        saga.compensate();
        throw e;
      }
      return flight + "+" + hotel;
    }
  }

  static final class DatabaseTrips implements Trips {
    private final DataSource dataSource;
    private final boolean hangWhileCancellingHotel;

    DatabaseTrips(DataSource dataSource, boolean hangWhileCancellingHotel) {
      this.dataSource = dataSource;
      this.hangWhileCancellingHotel = hangWhileCancellingHotel;
    }

    @Override
    public String bookFlight(String trip) {
      effect("bookFlight");
      return "F-" + trip;
    }

    @Override
    public String bookHotel(String trip) {
      effect("bookHotel");
      return "H-" + trip;
    }

    @Override
    public void bookCar(String trip) {
      record("bookCar");
      throw new NoCarsLeftException("no cars left for " + trip);
    }

    @Override
    public void cancelFlight(String flight) {
      effect("cancelFlight");
    }

    @Override
    public void cancelHotel(String hotel) {
      record("cancelHotel");
      if (hangWhileCancellingHotel) {
        try {
          Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      execute(
          "INSERT INTO trip.effects (idempotency_key, activity) VALUES (?, ?) ON CONFLICT DO NOTHING",
          ActivityContext.current().idempotencyKey(),
          "cancelHotel");
    }

    private void effect(String activity) {
      record(activity);
      execute(
          "INSERT INTO trip.effects (idempotency_key, activity) VALUES (?, ?) ON CONFLICT DO NOTHING",
          ActivityContext.current().idempotencyKey(),
          activity);
    }

    private void record(String activity) {
      execute(
          "INSERT INTO trip.calls (activity, idempotency_key, pid) VALUES (?, ?, ?)",
          activity,
          ActivityContext.current().idempotencyKey(),
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

  private TripApp() {}

  public static void main(String[] args) throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(args[0]);
    dataSource.setUser(args[1]);
    dataSource.setPassword(args[2]);
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(Duration.ofSeconds(2))
            .workflow(TripWorkflow.class, TripWorkflow::new)
            .activities(
                Trips.class,
                new DatabaseTrips(dataSource, HANG_WHILE_CANCELLING_HOTEL.equals(args[3])))
            .build();
    engine.start();
    Thread.sleep(Duration.ofMinutes(10));
  }
}
