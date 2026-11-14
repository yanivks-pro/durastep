package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.activity.ActivityRegistry.RegisteredActivity;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Failure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

public final class ActivityRuntime {

  private final ActivityRegistry registry;
  private final PayloadConverter converter;

  public ActivityRuntime(ActivityRegistry registry, PayloadConverter converter) {
    this.registry = registry;
    this.converter = converter;
  }

  public ActivityResult execute(
      String activity, Payload arguments, String workflowId, int scheduledSeq) {
    Optional<RegisteredActivity> registered = registry.find(activity);
    if (registered.isEmpty()) {
      return new Failed(
          new Failure(
              IllegalStateException.class.getName(),
              "no implementation is registered for activity " + activity));
    }
    Method method = registered.get().method();
    try {
      Object[] values = converter.fromArguments(arguments, method.getGenericParameterTypes());
      Object result =
          ActivityScope.callWith(
              new RunningActivity(workflowId, scheduledSeq),
              () -> invoke(registered.get().implementation(), method, values));
      return new Completed(converter.toPayload(result));
    } catch (Exception e) {
      return new Failed(Failure.of(e));
    }
  }

  private static Object invoke(Object implementation, Method method, Object[] values)
      throws Exception {
    try {
      return method.invoke(implementation, values);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      if (e.getCause() instanceof Error cause) {
        throw cause;
      }
      throw e;
    }
  }
}
