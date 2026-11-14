package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

final class ReplayContext implements WorkflowContext {

  private final String workflowId;
  private final ReplayHistory history;
  private final PayloadConverter converter;
  private final Random random;
  private final WorkflowSuspended suspension = new WorkflowSuspended();

  private int nextStep;
  private Instant now;
  private boolean suspended;
  private ScheduleActivity newCommand;
  private Failure activityFailure;
  private NonDeterminismException nonDeterminism;

  ReplayContext(String workflowId, ReplayHistory history, PayloadConverter converter) {
    this.workflowId = workflowId;
    this.history = history;
    this.converter = converter;
    this.random = new Random(seedFor(workflowId));
    this.now = history.startedAt();
  }

  @Override
  public <T> T activities(Class<T> activityInterface) {
    if (!activityInterface.isInterface()) {
      throw new IllegalArgumentException(activityInterface.getName() + " is not an interface");
    }
    Object stub =
        Proxy.newProxyInstance(
            activityInterface.getClassLoader(),
            new Class<?>[] {activityInterface},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, activityInterface);
              }
              return callActivity(activityInterface, method, args);
            });
    return activityInterface.cast(stub);
  }

  @Override
  public Instant now() {
    return now;
  }

  @Override
  public Random random() {
    return random;
  }

  @Override
  public UUID randomUUID() {
    long mostSignificant = (random.nextLong() & ~0xF000L) | 0x4000L;
    long leastSignificant = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(mostSignificant, leastSignificant);
  }

  boolean suspended() {
    return suspended;
  }

  boolean isSuspension(Throwable error) {
    return error == suspension;
  }

  ScheduleActivity newCommand() {
    return newCommand;
  }

  Failure activityFailure() {
    return activityFailure;
  }

  NonDeterminismException nonDeterminism() {
    return nonDeterminism;
  }

  int stepsReached() {
    return nextStep;
  }

  private Object callActivity(Class<?> activityInterface, Method method, Object[] args) {
    if (suspended) {
      throw suspension;
    }
    String activity = activityInterface.getSimpleName() + "." + method.getName();
    int stepIndex = nextStep++;
    if (stepIndex >= history.steps().size()) {
      newCommand = new ScheduleActivity(activity, converter.toArguments(args));
      throw suspend();
    }
    ReplayHistory.Step step = history.steps().get(stepIndex);
    if (!step.activity().equals(activity)) {
      nonDeterminism =
          new NonDeterminismException(
              "workflow "
                  + workflowId
                  + " called "
                  + activity
                  + " at step "
                  + (stepIndex + 1)
                  + ", but its history has "
                  + step.activity()
                  + " there");
      throw suspend();
    }
    if (!step.hasOutcome()) {
      throw suspend();
    }
    return switch (step.outcome().event()) {
      case ActivityCompleted completed -> {
        now = step.outcome().recordedAt();
        yield method.getReturnType() == void.class
            ? null
            : converter.fromPayload(completed.result(), method.getGenericReturnType());
      }
      case ActivityFailed failed -> {
        activityFailure = failed.failure();
        throw suspend();
      }
      default -> throw new IllegalStateException("unexpected outcome " + step.outcome());
    };
  }

  private WorkflowSuspended suspend() {
    suspended = true;
    return suspension;
  }

  @SuppressWarnings("ReferenceEquality")
  private static Object objectMethod(Object proxy, Method method, Object[] args, Class<?> type) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "Durastep activity stub for " + type.getName();
      default -> throw new UnsupportedOperationException(method.getName());
    };
  }

  private static long seedFor(String workflowId) {
    return UUID.nameUUIDFromBytes(workflowId.getBytes(StandardCharsets.UTF_8))
        .getMostSignificantBits();
  }
}
