package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class DurastepEngineTest {

  interface Greetings {
    String greet(String name);
  }

  static final class NotAnInterface {}

  @Test
  void aDataSourceIsRequired() {
    assertThatThrownBy(() -> DurastepEngine.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("dataSource");
  }

  @Test
  void anInvalidSchemaNameIsRejectedWhenBuilding() {
    assertThatThrownBy(
            () ->
                DurastepEngine.builder()
                    .dataSource(new PGSimpleDataSource())
                    .schema("Bad-Name")
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void activityMistakesAreRejectedAtRegistration() {
    assertThatThrownBy(
            () -> DurastepEngine.builder().activities(NotAnInterface.class, new NotAnInterface()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anIdOutsideTheAllowedLengthIsRejected() {
    DurastepClient client =
        DurastepEngine.builder().dataSource(new PGSimpleDataSource()).build().client();

    assertThatThrownBy(() -> client.start(GreetingWorkflow.class, "", "Ada"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.start(GreetingWorkflow.class, "x".repeat(256), "Ada"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static final class GreetingWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String name) {
      return ctx.activities(Greetings.class).greet(name);
    }
  }
}
