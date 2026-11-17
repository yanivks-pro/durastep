package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.Promise;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

final class ReplayContext implements WorkflowContext {

  static final int MAX_STATUS_LENGTH = 1_000;

  record Outcome(
      boolean mainFinished,
      Object result,
      Throwable thrown,
      boolean suspensionCaught,
      Throwable thrownAfterCatch) {}

  private enum Kind {
    RESULT,
    SIGNAL,
    CANCEL
  }

  private record Option(Kind kind, HistoryEntry entry) {

    int seq() {
      return entry.seq();
    }
  }

  private final String workflowId;
  private final ReplayHistory history;
  private final PayloadConverter converter;
  private final WorkflowSuspended suspension = new WorkflowSuspended();
  private final Semaphore turnEnded = new Semaphore(0);
  private final List<Branch> branches = new ArrayList<>();
  private final Deque<Branch> runnable = new ArrayDeque<>();
  private final List<Command> newCommands = new ArrayList<>();
  private final Set<Integer> consumedSignals = new HashSet<>();

  private Branch main;
  private Branch current;
  private int cursor = 1;
  private boolean aborting;
  private boolean cancellationDelivered;
  private String status;
  private boolean statusSet;
  private NonDeterminismException nonDeterminism;

  ReplayContext(String workflowId, ReplayHistory history, PayloadConverter converter) {
    this.workflowId = workflowId;
    this.history = history;
    this.converter = converter;
  }

  Outcome run(Supplier<?> mainCode) {
    main =
        new Branch(
            HistoryEvent.MAIN_BRANCH, mainCode, history.startedAt(), seedFor(workflowId), false);
    current = main;
    runnable.add(main);
    do {
      while (!runnable.isEmpty() && nonDeterminism == null && !main.finished) {
        Branch next = runnable.poll();
        takeTurn(next);
        if (next.finished) {
          runnable.addAll(next.waiters);
          next.waiters.clear();
        }
      }
    } while (nonDeterminism == null && !main.finished && deliverNextResult());
    boolean mainFinished = main.finished;
    Branch caught = stopUnfinishedBranches();
    return new Outcome(
        mainFinished,
        main.result,
        main.thrown,
        caught != null,
        caught == null ? null : caught.thrown);
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
  public <T> Promise<T> async(Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    if (aborting) {
      throw suspension;
    }
    Branch parent = current;
    String id = parent.id + "." + ++parent.children;
    Branch child =
        new Branch(id, body, parent.now, seedFor(workflowId + "/" + id), parent.cancelled);
    runnable.add(child);
    return new BranchPromise<>(child);
  }

  @Override
  public Promise<Void> async(Runnable body) {
    Objects.requireNonNull(body, "body");
    return this.<Void>async(
        () -> {
          body.run();
          return null;
        });
  }

  @Override
  public Instant now() {
    return current.now;
  }

  @Override
  public Random random() {
    return current.random;
  }

  @Override
  public UUID randomUUID() {
    Random random = current.random;
    long mostSignificant = (random.nextLong() & ~0xF000L) | 0x4000L;
    long leastSignificant = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(mostSignificant, leastSignificant);
  }

  boolean cancellationDelivered() {
    return cancellationDelivered;
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

  int mainStepsReached() {
    return main.nextStep;
  }

  Optional<String> branchInHistoryNeverStarted() {
    Set<String> started = new HashSet<>();
    branches.forEach(branch -> started.add(branch.id));
    return history.stepsByBranch().keySet().stream()
        .filter(branch -> !started.contains(branch))
        .findFirst();
  }

  @Override
  public void sleep(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("cannot sleep for a negative duration: " + duration);
    }
    reachStep(ReplayHistory.SLEEP, branch -> new StartTimer(duration, null, branch));
  }

  private Object callActivity(
      Class<?> activityInterface, Method method, Object[] args, ActivityOptions options) {
    String activity = activityInterface.getSimpleName() + "." + method.getName();
    HistoryEntry outcome =
        reachStep(
            activity,
            branch -> new ScheduleActivity(activity, converter.toArguments(args), options, branch));
    return switch (outcome.event()) {
      case ActivityCompleted completed ->
          method.getReturnType() == void.class
              ? null
              : converter.fromPayload(completed.result(), method.getGenericReturnType());
      case ActivityFailed failed ->
          throw new ActivityFailedException(
              activity, failed.failure().type(), failed.failure().message(), failed.attempts());
      default -> throw new IllegalStateException("unexpected outcome " + outcome);
    };
  }

  @Override
  public <T> T awaitSignal(String name, Class<T> type) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Branch branch = mainOnly("awaitSignal");
    List<Option> options = new ArrayList<>();
    nextSignal(name, Integer.MAX_VALUE).ifPresent(signal -> options.add(signalOption(signal)));
    cancelOption(branch).ifPresent(options::add);
    Option chosen = await(branch, options);
    if (chosen.kind() == Kind.CANCEL) {
      throw new CancelledException(workflowId);
    }
    return receive(chosen.entry(), type);
  }

  @Override
  public <T> Optional<T> awaitSignal(String name, Class<T> type, Duration timeout) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("cannot wait for a negative duration: " + timeout);
    }
    Branch branch = mainOnly("awaitSignal");
    String stepName = "awaitSignal(" + name + ")";
    List<ReplayHistory.Step> steps = history.stepsOf(branch.id);
    int stepIndex = branch.nextStep;
    if (notYetScheduledWhenCancelled(branch, steps, stepIndex)) {
      Optional<HistoryEntry> buffered = nextSignal(name, Integer.MAX_VALUE);
      if (buffered.isEmpty() || buffered.get().seq() > history.cancelRequest().seq()) {
        throw cancelHere(branch);
      }
    }
    List<Option> options = new ArrayList<>();
    if (stepIndex >= steps.size()) {
      branch.nextStep++;
      newCommands.add(new StartTimer(timeout, name, branch.id));
      nextSignal(name, Integer.MAX_VALUE).ifPresent(signal -> options.add(signalOption(signal)));
    } else {
      ReplayHistory.Step step = steps.get(stepIndex);
      branch.nextStep++;
      if (!step.name().equals(stepName)) {
        throw mismatch(branch, stepName, stepIndex, step);
      }
      int firedAt = step.hasOutcome() ? step.outcome().seq() : Integer.MAX_VALUE;
      nextSignal(name, firedAt).ifPresent(signal -> options.add(signalOption(signal)));
      if (step.hasOutcome()) {
        options.add(new Option(Kind.RESULT, step.outcome()));
      }
    }
    cancelOption(branch).ifPresent(options::add);
    Option chosen = await(branch, options);
    return switch (chosen.kind()) {
      case SIGNAL -> Optional.of(receive(chosen.entry(), type));
      case RESULT -> Optional.empty();
      case CANCEL -> throw new CancelledException(workflowId);
    };
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

  private HistoryEntry reachStep(String name, Function<String, Command> newStep) {
    if (aborting) {
      throw suspension;
    }
    Branch branch = current;
    List<ReplayHistory.Step> steps = history.stepsOf(branch.id);
    int stepIndex = branch.nextStep;
    if (notYetScheduledWhenCancelled(branch, steps, stepIndex)) {
      throw cancelHere(branch);
    }
    if (stepIndex >= steps.size()) {
      branch.nextStep++;
      newCommands.add(newStep.apply(branch.id));
      throw waitForever(branch);
    }
    ReplayHistory.Step step = steps.get(stepIndex);
    branch.nextStep++;
    if (!step.name().equals(name)) {
      throw mismatch(branch, name, stepIndex, step);
    }
    List<Option> options = new ArrayList<>();
    if (step.hasOutcome()) {
      options.add(new Option(Kind.RESULT, step.outcome()));
    }
    cancelOption(branch).ifPresent(options::add);
    Option chosen = await(branch, options);
    if (chosen.kind() == Kind.CANCEL) {
      throw new CancelledException(workflowId);
    }
    return chosen.entry();
  }

  private boolean notYetScheduledWhenCancelled(
      Branch branch, List<ReplayHistory.Step> steps, int stepIndex) {
    if (history.cancelRequest() == null || branch.cancelled) {
      return false;
    }
    return stepIndex >= steps.size()
        || steps.get(stepIndex).scheduled().seq() > history.cancelRequest().seq();
  }

  private Optional<Option> cancelOption(Branch branch) {
    if (history.cancelRequest() == null || branch.cancelled) {
      return Optional.empty();
    }
    return Optional.of(new Option(Kind.CANCEL, history.cancelRequest()));
  }

  private CancelledException cancelHere(Branch branch) {
    await(branch, List.of(cancelOption(branch).orElseThrow()));
    return new CancelledException(workflowId);
  }

  private Option await(Branch branch, List<Option> options) {
    if (aborting) {
      throw suspension;
    }
    Option earliest = earliest(options);
    if (earliest == null || earliest.seq() > cursor) {
      branch.options = List.copyOf(options);
      endTurn(branch);
      earliest = branch.delivered;
      branch.delivered = null;
    }
    if (earliest.kind() == Kind.CANCEL) {
      branch.cancelled = true;
      cancellationDelivered = true;
    }
    branch.advanceTo(earliest.entry());
    return earliest;
  }

  private WorkflowSuspended waitForever(Branch branch) {
    await(branch, List.of());
    throw new IllegalStateException("a branch waiting forever was resumed");
  }

  private Object awaitBranch(Branch target) {
    if (aborting) {
      throw suspension;
    }
    if (!target.finished) {
      target.waiters.add(current);
      endTurn(current);
    }
    if (target.thrown instanceof RuntimeException e) {
      throw e;
    }
    if (target.thrown instanceof Error e) {
      throw e;
    }
    if (target.thrown != null) {
      throw new IllegalStateException(target.thrown);
    }
    return target.result;
  }

  private boolean deliverNextResult() {
    Option next = null;
    for (Branch branch : branches) {
      if (branch.options != null) {
        Option earliest = earliest(branch.options);
        if (earliest != null && (next == null || earliest.seq() < next.seq())) {
          next = earliest;
        }
      }
    }
    if (next == null) {
      return false;
    }
    cursor = next.seq();
    for (Branch branch : branches) {
      if (branch.options != null) {
        for (Option option : branch.options) {
          if (option.seq() == cursor) {
            branch.delivered = option;
            branch.options = null;
            runnable.add(branch);
            break;
          }
        }
      }
    }
    return true;
  }

  private Branch stopUnfinishedBranches() {
    aborting = true;
    Branch caught = null;
    for (Branch branch : List.copyOf(branches)) {
      if (branch.thread != null && !branch.finished) {
        takeTurn(branch);
        if (caught == null && branch.thrown != suspension) {
          caught = branch;
        }
      }
    }
    for (Branch branch : branches) {
      if (branch.thread != null) {
        joinUninterruptibly(branch.thread);
      }
    }
    return caught;
  }

  private void takeTurn(Branch branch) {
    current = branch;
    if (branch.thread == null) {
      branch.thread = Thread.ofVirtual().name(threadName(branch)).start(branch::run);
    } else {
      branch.resume.release();
    }
    turnEnded.acquireUninterruptibly();
  }

  private void endTurn(Branch branch) {
    turnEnded.release();
    branch.resume.acquireUninterruptibly();
    if (aborting) {
      throw suspension;
    }
  }

  private Branch mainOnly(String operation) {
    if (aborting) {
      throw suspension;
    }
    if (current != main) {
      throw new IllegalStateException(
          operation
              + " can only be called from the workflow's main code, not from a branch started"
              + " with async");
    }
    return current;
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

  private static Option signalOption(HistoryEntry signal) {
    return new Option(Kind.SIGNAL, signal);
  }

  private <T> T receive(HistoryEntry signal, Class<T> type) {
    consumedSignals.add(signal.seq());
    return type.cast(converter.fromPayload(((SignalReceived) signal.event()).payload(), type));
  }

  private WorkflowSuspended mismatch(
      Branch branch, String name, int stepIndex, ReplayHistory.Step step) {
    nonDeterminism =
        new NonDeterminismException(
            "workflow "
                + workflowId
                + " called "
                + name
                + " at step "
                + (stepIndex + 1)
                + (branch == main ? "" : " of branch " + branch.id)
                + ", but its history has "
                + step.name()
                + " there");
    return waitForever(branch);
  }

  private String threadName(Branch branch) {
    return "durastep-workflow-"
        + workflowId
        + (branch.id.equals(HistoryEvent.MAIN_BRANCH) ? "" : "-" + branch.id);
  }

  private static Option earliest(List<Option> options) {
    Option earliest = null;
    for (Option option : options) {
      if (earliest == null || option.seq() < earliest.seq()) {
        earliest = option;
      }
    }
    return earliest;
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

  private static long seedFor(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
  }

  private static void joinUninterruptibly(Thread thread) {
    boolean interrupted = false;
    while (true) {
      try {
        thread.join();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private final class Branch {
    final String id;
    final Supplier<?> code;
    final Random random;
    final Semaphore resume = new Semaphore(0);
    final List<Branch> waiters = new ArrayList<>();
    Instant now;
    int nextStep;
    int children;
    boolean cancelled;
    Thread thread;
    boolean finished;
    Object result;
    Throwable thrown;
    List<Option> options;
    Option delivered;

    Branch(String id, Supplier<?> code, Instant now, long seed, boolean cancelled) {
      this.id = id;
      this.code = code;
      this.now = now;
      this.random = new Random(seed);
      this.cancelled = cancelled;
      branches.add(this);
    }

    void advanceTo(HistoryEntry entry) {
      if (entry.recordedAt().isAfter(now)) {
        now = entry.recordedAt();
      }
    }

    void run() {
      try {
        result = code.get();
      } catch (Throwable error) {
        thrown = error;
      } finally {
        finished = true;
        turnEnded.release();
      }
    }
  }

  private final class BranchPromise<T> implements Promise<T> {
    private final Branch branch;

    BranchPromise(Branch branch) {
      this.branch = branch;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
      return (T) awaitBranch(branch);
    }

    @Override
    public boolean isDone() {
      return branch.finished;
    }
  }
}
