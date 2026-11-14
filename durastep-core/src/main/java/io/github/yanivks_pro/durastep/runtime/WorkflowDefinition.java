package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.Workflow;
import java.util.Objects;
import java.util.function.Supplier;

public record WorkflowDefinition<I, O>(
    String type, Class<I> inputType, Supplier<? extends Workflow<I, O>> factory) {

  public WorkflowDefinition {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(inputType, "inputType");
    Objects.requireNonNull(factory, "factory");
  }
}
