package io.github.yanivks_pro.durastep.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.trip.TripExample;
import io.github.yanivks_pro.durastep.examples.trip.TripExample.Result;
import io.github.yanivks_pro.durastep.examples.trip.TripExample.Scenario;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class TripExampleIT {

  private static PostgreSQLContainer postgres;
  private static DataSource dataSource;

  private final Narrator narrator = new Narrator(System.out);

  @BeforeAll
  static void startPostgres() {
    postgres = ExamplePostgres.newContainer();
    postgres.start();
    dataSource = ExamplePostgres.dataSourceFor(postgres);
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void flightAndHotelAreBookedAtTheSameTimeThenTheCar() throws Exception {
    Result result = TripExample.run(dataSource, Scenario.HAPPY, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(result.outcome()).contains("flight", "Hotel Alfama", "small car");
    assertThat(result.stillBooked()).hasSize(3);
    assertThat(result.took()).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void whenNoCarIsLeftTheHotelAndTheFlightAreCancelled() throws Exception {
    Result result = TripExample.run(dataSource, Scenario.NO_CARS, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.FAILED);
    assertThat(result.outcome()).isEqualTo("failed: no cars left in Lisbon");
    assertThat(result.stillBooked()).isEmpty();
  }

  @Test
  void whenTheTravellerCancelsEverythingIsUndone() throws Exception {
    Result result = TripExample.run(dataSource, Scenario.CUSTOMER_CANCELS, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.CANCELLED);
    assertThat(result.outcome()).isEqualTo("cancelled");
    assertThat(result.stillBooked()).isEmpty();
  }
}
