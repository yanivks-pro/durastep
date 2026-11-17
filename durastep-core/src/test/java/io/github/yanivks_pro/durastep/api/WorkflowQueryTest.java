package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowQueryTest {

  static final class SomeWorkflow implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return input;
    }
  }

  @Test
  void aNewQueryHasNoFiltersAndTheDefaultLimit() {
    WorkflowQuery query = WorkflowQuery.all();

    assertThat(query.stateFilter()).isEmpty();
    assertThat(query.typeFilter()).isEmpty();
    assertThat(query.blockedFilter()).isEmpty();
    assertThat(query.maxResults()).isEqualTo(WorkflowQuery.DEFAULT_LIMIT);
  }

  @Test
  void eachFilterReturnsANewQueryAndLeavesTheOriginalAlone() {
    WorkflowQuery all = WorkflowQuery.all();

    WorkflowQuery narrowed =
        all.state(WorkflowState.RUNNING).type(SomeWorkflow.class).blocked(true).limit(5);

    assertThat(narrowed.stateFilter()).contains(WorkflowState.RUNNING);
    assertThat(narrowed.typeFilter()).contains("SomeWorkflow");
    assertThat(narrowed.blockedFilter()).contains(true);
    assertThat(narrowed.maxResults()).isEqualTo(5);
    assertThat(all.stateFilter()).isEmpty();
  }

  @Test
  void theLimitMustBeBetweenOneAndTheMaximum() {
    assertThatThrownBy(() -> WorkflowQuery.all().limit(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WorkflowQuery.all().limit(WorkflowQuery.MAX_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(WorkflowQuery.all().limit(WorkflowQuery.MAX_LIMIT).maxResults())
        .isEqualTo(WorkflowQuery.MAX_LIMIT);
  }
}
