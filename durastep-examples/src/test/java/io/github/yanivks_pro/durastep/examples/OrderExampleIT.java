package io.github.yanivks_pro.durastep.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.order.OrderExample;
import io.github.yanivks_pro.durastep.examples.order.OrderExample.Result;
import io.github.yanivks_pro.durastep.examples.order.OrderExample.Scenario;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OrderExampleIT {

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
  void anOrderIsChargedReservedShippedAndConfirmed() throws Exception {
    Result result = OrderExample.run(dataSource, Scenario.HAPPY, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(result.trackingNumber()).startsWith("TRK-");
    assertThat(result.chargeCalls()).isEqualTo(1);
    assertThat(result.charges()).isEqualTo(1);
  }

  @Test
  void aFlakyPaymentGatewayIsRetriedAndTheCardIsChargedOnce() throws Exception {
    Result result = OrderExample.run(dataSource, Scenario.FLAKY_PAYMENTS, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(result.chargeCalls()).isEqualTo(3);
    assertThat(result.charges()).isEqualTo(1);
  }
}
