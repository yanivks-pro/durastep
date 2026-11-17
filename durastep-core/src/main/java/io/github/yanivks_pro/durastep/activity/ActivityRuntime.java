package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.activity.ActivityRegistry.RegisteredActivity;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.ActivityTimeoutException;
import io.github.yanivks_pro.durastep.api.PayloadTooLargeException;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Failure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ActivityRuntime {

  private final ActivityRegistry registry;
  private final PayloadConverter converter;

  public ActivityRuntime(ActivityRegistry registry, PayloadConverter converter) {
    this.registry = registry;
    this.converter = converter;
  }

  public ActivityResult execute(
      String activity,
      Payload arguments,
      String workflowId,
      int scheduledSeq,
      ActivityOptions options)
      throws InterruptedException {
    return execute(activity, arguments, workflowId, scheduledSeq, options, Heartbeat.NONE);
  }

  public ActivityResult execute(
      String activity,
      Payload arguments,
      String workflowId,
      int scheduledSeq,
      ActivityOptions options,
      Heartbeat heartbeat)
      throws InterruptedException {
    Optional<RegisteredActivity> registered = registry.find(activity);
    if (registered.isEmpty()) {
      return new Failed(
          new Failure(
              IllegalStateException.class.getName(),
              "no implementation is registered for activity " + activity),
          false);
    }
    Method method = registered.get().method();
    Object[] values;
    try {
      values = converter.fromArguments(arguments, method.getGenericParameterTypes());
    } catch (RuntimeException e) {
      return new Failed(Failure.of(e), false);
    }

    CompletableFuture<Object> outcome = new CompletableFuture<>();
    RunningActivity context =
        options
            .heartbeatTimeout()
            .map(
                timeout ->
                    new RunningActivity(
                        workflowId, scheduledSeq, heartbeat, timeout, System::nanoTime))
            .orElseGet(() -> new RunningActivity(workflowId, scheduledSeq));
    Thread attempt =
        Thread.ofVirtual()
            .name("durastep-activity-" + context.idempotencyKey())
            .start(
                () -> {
                  try {
                    outcome.complete(
                        ActivityScope.callWith(
                            context,
                            () -> invoke(registered.get().implementation(), method, values)));
                  } catch (Throwable e) {
                    outcome.completeExceptionally(e);
                  }
                });
    try {
      Object result = outcome.get(options.attemptTimeout().toNanos(), TimeUnit.NANOSECONDS);
      try {
        return new Completed(converter.toPayload(result));
      } catch (PayloadTooLargeException e) {
        return new Failed(Failure.of(e), false);
      }
    } catch (TimeoutException e) {
      attempt.interrupt();
      ActivityTimeoutException timeout =
          new ActivityTimeoutException(activity, options.attemptTimeout());
      return new Failed(Failure.of(timeout), options.retryPolicy().isRetryable(timeout));
    } catch (InterruptedException e) {
      attempt.interrupt();
      throw e;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      return new Failed(Failure.of(cause), options.retryPolicy().isRetryable(cause));
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
