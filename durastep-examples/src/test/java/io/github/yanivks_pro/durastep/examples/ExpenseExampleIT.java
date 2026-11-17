package io.github.yanivks_pro.durastep.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseExample;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseExample.Result;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseExample.Scenario;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ExpenseExampleIT {

  private static final long CLAIM_CENTS = 12_000;

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
  void theManagerApprovesAndTheClaimIsPaid() throws Exception {
    Result result = ExpenseExample.run(dataSource, Scenario.APPROVE, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(result.outcome()).isEqualTo("paid");
    assertThat(result.paidCents()).isEqualTo(CLAIM_CENTS);
    assertThat(result.told()).containsExactly("approved by Grace (manager) and paid");
  }

  @Test
  void theManagerRejectsAndNothingIsPaid() throws Exception {
    Result result = ExpenseExample.run(dataSource, Scenario.REJECT, narrator);

    assertThat(result.outcome()).isEqualTo("rejected");
    assertThat(result.finalStatus()).isEqualTo("rejected by Grace (manager)");
    assertThat(result.paidCents()).isZero();
  }

  @Test
  void withoutAnAnswerFromTheManagerTheDirectorDecides() throws Exception {
    Result result = ExpenseExample.run(dataSource, Scenario.ESCALATE, narrator);

    assertThat(result.outcome()).isEqualTo("paid");
    assertThat(result.told()).containsExactly("approved by Linus (director) and paid");
    assertThat(result.paidCents()).isEqualTo(CLAIM_CENTS);
  }

  @Test
  void whenNobodyAnswersTheClaimIsRejected() throws Exception {
    Result result = ExpenseExample.run(dataSource, Scenario.NOBODY_ANSWERS, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.COMPLETED);
    assertThat(result.outcome()).isEqualTo("rejected");
    assertThat(result.finalStatus()).isEqualTo("rejected: nobody answered in time");
    assertThat(result.paidCents()).isZero();
  }

  @Test
  void aWithdrawnClaimIsCancelledAndTheEmployeeIsTold() throws Exception {
    Result result = ExpenseExample.run(dataSource, Scenario.WITHDRAW, narrator);

    assertThat(result.state()).isEqualTo(WorkflowState.CANCELLED);
    assertThat(result.finalStatus()).isEqualTo("withdrawn by Ada");
    assertThat(result.told()).containsExactly("your claim was withdrawn");
    assertThat(result.paidCents()).isZero();
  }
}
