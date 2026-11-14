package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.api.ActivityContext;

public final class ActivityScope {

  private static final ScopedValue<ActivityContext> CURRENT = ScopedValue.newInstance();

  private ActivityScope() {}

  public static ActivityContext current() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException(
          "ActivityContext.current() can only be called from inside an activity");
    }
    return CURRENT.get();
  }

  static <R, X extends Throwable> R callWith(
      ActivityContext context, ScopedValue.CallableOp<? extends R, X> operation) throws X {
    return ScopedValue.where(CURRENT, context).call(operation);
  }
}
