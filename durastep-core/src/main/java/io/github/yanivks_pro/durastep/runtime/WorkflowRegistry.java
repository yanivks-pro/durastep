package io.github.yanivks_pro.durastep.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class WorkflowRegistry {

  private final Map<String, WorkflowDefinition<?, ?>> definitions = new HashMap<>();

  public WorkflowRegistry register(WorkflowDefinition<?, ?> definition) {
    if (definitions.putIfAbsent(definition.type(), definition) != null) {
      throw new IllegalArgumentException(
          "a workflow named " + definition.type() + " is already registered");
    }
    return this;
  }

  public Optional<WorkflowDefinition<?, ?>> find(String type) {
    return Optional.ofNullable(definitions.get(type));
  }
}
