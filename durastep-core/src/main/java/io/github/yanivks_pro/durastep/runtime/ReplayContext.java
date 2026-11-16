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
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.SignalReceived;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

final class ReplayContext implements WorkflowContext {

  static final int MAX_STATUS_LENGTH = 1_000;

  private final String workflowId;
  private final ReplayHistory history;
  private final PayloadConverter converter;
  private final Random random;
  private final WorkflowSuspended suspension = new WorkflowSuspended();

  private int nextStep;
  private Instant now;
  private boolean suspended;
  private final List<Command> newCommands = new ArrayList<>();
  private final Set<Integer> consumedSignals = new HashSet<>();
  private String status;
  private boolean statusSet;
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

  List<Command> newCommands() {
    return List.copyOf(newCommands);
  }

  Optional<String> statusSet() {
    return statusSet ? Optional.of(status) : Optional.empty();
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
    advanceTo(step.outcome());
  }

  private Object callActivity(
      Class<?> activityInterface, Method method, Object[] args, ActivityOptions options) {
    String activity = activityInterface.getSimpleName() + "." + method.getName();
    ReplayHistory.Step step =
        reachStep(
            activity, () -> new ScheduleActivity(activity, converter.toArguments(args), options));
    advanceTo(step.outcome());
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

  @Override
  public <T> T awaitSignal(String name, Class<T> type) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (suspended) {
      throw suspension;
    }
    Optional<HistoryEntry> signal = nextSignal(name, Integer.MAX_VALUE);
    if (signal.isEmpty()) {
      throw suspend();
    }
    return receive(signal.get(), type);
  }

  @Override
  public <T> Optional<T> awaitSignal(String name, Class<T> type, Duration timeout) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("cannot wait for a negative duration: " + timeout);
    }
    if (suspended) {
      throw suspension;
    }
    String stepName = "awaitSignal(" + name + ")";
    int stepIndex = nextStep++;
    if (stepIndex >= history.steps().size()) {
      newCommands.add(new StartTimer(timeout, name));
      Optional<HistoryEntry> buffered = nextSignal(name, Integer.MAX_VALUE);
      if (buffered.isEmpty()) {
        throw suspend();
      }
      return Optional.of(receive(buffered.get(), type));
    }
    ReplayHistory.Step step = history.steps().get(stepIndex);
    if (!step.name().equals(stepName)) {
      throw mismatch(stepName, stepIndex, step);
    }
    int firedAt = step.hasOutcome() ? step.outcome().seq() : Integer.MAX_VALUE;
    Optional<HistoryEntry> signal = nextSignal(name, firedAt);
    if (signal.isPresent()) {
      return Optional.of(receive(signal.get(), type));
    }
    if (!step.hasOutcome()) {
      throw suspend();
    }
    advanceTo(step.outcome());
    return Optional.empty();
  }

  @Override
  public void setStatus(String status) {
    Objects.requireNonNull(status, "status");
    if (status.length() > MAX_STATUS_LENGTH) {
      throw new IllegalArgumentException(
          "a status may have at most " + MAX_STATUS_LENGTH + " characters");
    }
    this.status = status;
    this.statusSet = true;
  }

  private Optional<HistoryEntry> nextSignal(String name, int beforeSeq) {
    for (HistoryEntry entry : history.signals()) {
      if (entry.seq() < beforeSeq
          && !consumedSignals.contains(entry.seq())
          && ((SignalReceived) entry.event()).name().equals(name)) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  private <T> T receive(HistoryEntry signal, Class<T> type) {
    consumedSignals.add(signal.seq());
    advanceTo(signal);
    return type.cast(converter.fromPayload(((SignalReceived) signal.event()).payload(), type));
  }

  private void advanceTo(HistoryEntry entry) {
    if (entry.recordedAt().isAfter(now)) {
      now = entry.recordedAt();
    }
  }

  private WorkflowSuspended mismatch(String name, int stepIndex, ReplayHistory.Step step) {
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
    return suspend();
  }

  private ReplayHistory.Step reachStep(String name, Supplier<Command> newStep) {
    if (suspended) {
      throw suspension;
    }
    int stepIndex = nextStep++;
    if (stepIndex >= history.steps().size()) {
      newCommands.add(newStep.get());
      throw suspend();
    }
    ReplayHistory.Step step = history.steps().get(stepIndex);
    if (!step.name().equals(name)) {
      throw mismatch(name, stepIndex, step);
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
