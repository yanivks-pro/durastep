package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

final class ReplayContext implements WorkflowContext {

  private final String workflowId;
  private final ReplayHistory history;
  private final PayloadConverter converter;
  private final Random random;
  private final WorkflowSuspended suspension = new WorkflowSuspended();

  private int nextStep;
  private Instant now;
  private boolean suspended;
  private Command newCommand;
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
    return activities(activityInterface, ActivityOptions.DEFAULT);
  }

  @Override
  public <T> T activities(Class<T> activityInterface, ActivityOptions options) {
    Objects.requireNonNull(options, "options");
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
              return callActivity(activityInterface, method, args, options);
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

  Command newCommand() {
    return newCommand;
  }

  NonDeterminismException nonDeterminism() {
    return nonDeterminism;
  }

  int stepsReached() {
    return nextStep;
  }

  @Override
  public void sleep(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("cannot sleep for a negative duration: " + duration);
    }
    ReplayHistory.Step step = reachStep(ReplayHistory.SLEEP, () -> new StartTimer(duration));
    now = step.outcome().recordedAt();
  }

  private Object callActivity(
      Class<?> activityInterface, Method method, Object[] args, ActivityOptions options) {
    String activity = activityInterface.getSimpleName() + "." + method.getName();
    ReplayHistory.Step step =
        reachStep(
            activity, () -> new ScheduleActivity(activity, converter.toArguments(args), options));
    now = step.outcome().recordedAt();
    return switch (step.outcome().event()) {
      case ActivityCompleted completed ->
          method.getReturnType() == void.class
              ? null
              : converter.fromPayload(completed.result(), method.getGenericReturnType());
      case ActivityFailed failed ->
          throw new ActivityFailedException(
              activity, failed.failure().type(), failed.failure().message(), failed.attempts());
      default -> throw new IllegalStateException("unexpected outcome " + step.outcome());
    };
  }

  private ReplayHistory.Step reachStep(String name, Supplier<Command> newStep) {
    if (suspended) {
      throw suspension;
    }
    int stepIndex = nextStep++;
    if (stepIndex >= history.steps().size()) {
      newCommand = newStep.get();
      throw suspend();
    }
    ReplayHistory.Step step = history.steps().get(stepIndex);
    if (!step.name().equals(name)) {
      nonDeterminism =
          new NonDeterminismException(
              "workflow "
                  + workflowId
                  + " called "
                  + name
                  + " at step "
                  + (stepIndex + 1)
                  + ", but its history has "
                  + step.name()
                  + " there");
      throw suspend();
    }
    if (!step.hasOutcome()) {
      throw suspend();
    }
    return step;
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
