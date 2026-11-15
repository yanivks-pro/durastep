package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.Workflow;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public final class WorkflowTypes {

  private WorkflowTypes() {}

  public static String nameOf(Class<?> workflowClass) {
    return workflowClass.getSimpleName();
  }

  public static Class<?> inputOf(Class<?> workflowClass) {
    Type input = typeArguments(workflowClass)[0];
    if (input instanceof Class<?> inputClass) {
      return inputClass;
    }
    throw new IllegalArgumentException(
        workflowClass.getName()
            + " must declare a plain class as its input type, such as a record, but declares "
            + input.getTypeName());
  }

  public static Type outputOf(Class<?> workflowClass) {
    return typeArguments(workflowClass)[1];
  }

  private static Type[] typeArguments(Class<?> workflowClass) {
    for (Class<?> c = workflowClass; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Type implemented : c.getGenericInterfaces()) {
        if (implemented instanceof ParameterizedType parameterized
            && parameterized.getRawType() == Workflow.class) {
          return parameterized.getActualTypeArguments();
        }
      }
    }
    throw new IllegalArgumentException(
        workflowClass.getName() + " must implement Workflow<I, O> with concrete type arguments");
  }
}
