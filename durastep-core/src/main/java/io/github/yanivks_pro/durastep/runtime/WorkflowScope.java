package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.Promise;
import java.util.List;

public final class WorkflowScope {

  static final ScopedValue<ReplayContext> CURRENT = ScopedValue.newInstance();

  private WorkflowScope() {}

  public static Promise<Void> allOf(List<Promise<?>> promises) {
    return current("Promise.allOf").allOf(promises);
  }

  public static <T> Promise<T> anyOf(List<Promise<? extends T>> promises) {
    return current("Promise.anyOf").anyOf(promises);
  }

  private static ReplayContext current(String operation) {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException(operation + " can only be called from workflow code");
    }
    return CURRENT.get();
  }
}
