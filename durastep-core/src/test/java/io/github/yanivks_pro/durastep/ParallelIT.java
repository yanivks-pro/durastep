package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ParallelIT {

  private static final Duration BOOKING_TIME = Duration.ofSeconds(1);

  interface Trips {
    String bookFlight(String trip);

    String bookHotel(String trip);

    String confirm(String booking);
  }

  static final class SlowTrips implements Trips {
    @Override
    public String bookFlight(String trip) {
      return slowly("flight for " + trip);
    }

    @Override
    public String bookHotel(String trip) {
      return slowly("hotel for " + trip);
    }

    @Override
    public String confirm(String booking) {
      return booking + " (confirmed)";
    }

    private static String slowly(String booking) {
      try {
        Thread.sleep(BOOKING_TIME);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return booking;
    }
  }

  static final class TripWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String trip) {
      Trips trips = ctx.activities(Trips.class);
      Promise<String> flight = ctx.async(() -> trips.confirm(trips.bookFlight(trip)));
      Promise<String> hotel = ctx.async(() -> trips.confirm(trips.bookHotel(trip)));
      return flight.get() + " and " + hotel.get();
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
  void startEngine() {
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(TripWorkflow.class, TripWorkflow::new)
            .activities(Trips.class, new SlowTrips())
            .build();
    engine.start();
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void twoOneSecondBookingsInParallelBranchesTakeAboutOneSecondTogether() throws Exception {
    long starting = System.nanoTime();
    WorkflowHandle<String> trip = engine.client().start(TripWorkflow.class, "trip-1", "trip-1");

    String result = trip.result(Duration.ofSeconds(20));
    Duration took = Duration.ofNanos(System.nanoTime() - starting);

    assertThat(result).isEqualTo("flight for trip-1 (confirmed) and hotel for trip-1 (confirmed)");
    assertThat(took).isLessThan(BOOKING_TIME.multipliedBy(2));
    assertThat(stepsByBranch("trip-1"))
        .containsExactlyInAnyOrder(
            "0.1 Trips.bookFlight",
            "0.2 Trips.bookHotel",
            "0.1 Trips.confirm",
            "0.2 Trips.confirm");
    assertThat(InvariantChecker.violations(dataSource)).isEmpty();
  }

  private static List<String> stepsByBranch(String workflowId) throws SQLException {
    List<String> steps = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT payload->>'branch', payload->>'activity' FROM durastep.history_events"
                    + " WHERE workflow_id = '"
                    + workflowId
                    + "' AND event_type = 'ACTIVITY_SCHEDULED' ORDER BY seq")) {
      while (row.next()) {
        steps.add(row.getString(1) + " " + row.getString(2));
      }
    }
    return steps;
  }
}
