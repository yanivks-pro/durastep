package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Order;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {

  @Test
  void takesItsNameFromTheClassAndItsInputTypeFromTheWorkflowInterface() {
    WorkflowDefinition<?, ?> definition =
        WorkflowDefinition.of(OrderWorkflow.class, OrderWorkflow::new);

    assertThat(definition.type()).isEqualTo("OrderWorkflow");
    assertThat(definition.inputType()).isEqualTo(Order.class);
  }

  abstract static class BaseWorkflow implements Workflow<Order, String> {}

  static final class InheritedWorkflow extends BaseWorkflow {
    @Override
    public String run(WorkflowContext ctx, Order input) {
      return input.id();
    }
  }

  @Test
  void findsTheInputTypeOnASuperclass() {
    assertThat(WorkflowDefinition.of(InheritedWorkflow.class, InheritedWorkflow::new).inputType())
        .isEqualTo(Order.class);
  }

  static final class ListInputWorkflow implements Workflow<List<String>, String> {
    @Override
    public String run(WorkflowContext ctx, List<String> input) {
      return String.join(",", input);
    }
  }

  @Test
  void rejectsAGenericInputType() {
    assertThatThrownBy(() -> WorkflowDefinition.of(ListInputWorkflow.class, ListInputWorkflow::new))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plain class as its input type");
  }

  @Test
  void rejectsTwoWorkflowsWithTheSameName() {
    WorkflowRegistry registry = new WorkflowRegistry();
    registry.register(WorkflowDefinition.of(OrderWorkflow.class, OrderWorkflow::new));

    assertThatThrownBy(
            () -> registry.register(WorkflowDefinition.of(OrderWorkflow.class, OrderWorkflow::new)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OrderWorkflow is already registered");
  }
}
