package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.Workflow;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Supplier;

public record WorkflowDefinition<I, O>(
    String type, Class<I> inputType, Supplier<? extends Workflow<I, O>> factory) {

  public WorkflowDefinition {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(inputType, "inputType");
    Objects.requireNonNull(factory, "factory");
  }

  public static <I, O, W extends Workflow<I, O>> WorkflowDefinition<I, O> of(
      Class<W> workflowClass, Supplier<W> factory) {
    @SuppressWarnings("unchecked")
    Class<I> inputType = (Class<I>) inputTypeOf(workflowClass);
    return new WorkflowDefinition<>(workflowClass.getSimpleName(), inputType, factory);
  }

  private static Class<?> inputTypeOf(Class<?> workflowClass) {
    for (Class<?> c = workflowClass; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Type implemented : c.getGenericInterfaces()) {
        if (implemented instanceof ParameterizedType parameterized
            && parameterized.getRawType() == Workflow.class) {
          if (parameterized.getActualTypeArguments()[0] instanceof Class<?> input) {
            return input;
          }
          throw new IllegalArgumentException(
              workflowClass.getName()
                  + " must declare a plain class as its input type, such as a record, but declares "
                  + parameterized.getActualTypeArguments()[0].getTypeName());
        }
      }
    }
    throw new IllegalArgumentException(
        workflowClass.getName() + " must implement Workflow<I, O> with concrete type arguments");
  }
}
