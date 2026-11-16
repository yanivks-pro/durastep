package io.github.yanivks_pro.durastep.worker;

import io.github.yanivks_pro.durastep.activity.ActivityResult;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.activity.ActivityRuntime;
import io.github.yanivks_pro.durastep.activity.Backoff;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.runtime.Command;
import io.github.yanivks_pro.durastep.runtime.WorkflowBlockedException;
import io.github.yanivks_pro.durastep.runtime.WorkflowDefinition;
import io.github.yanivks_pro.durastep.runtime.WorkflowRegistry;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import io.github.yanivks_pro.durastep.store.ActivityToRun;
import io.github.yanivks_pro.durastep.store.ClaimedTask;
import io.github.yanivks_pro.durastep.store.CrashPoint;
import io.github.yanivks_pro.durastep.store.CrashPoints;
import io.github.yanivks_pro.durastep.store.DatabaseHealth;
import io.github.yanivks_pro.durastep.store.DecisionInput;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StaleTaskException;
import io.github.yanivks_pro.durastep.store.StoreException;
import io.github.yanivks_pro.durastep.store.TaskKind;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Worker {

  public static final String DEFAULT_QUEUE = JdbcWorkflowStore.DEFAULT_QUEUE;
  public static final Duration TIMER_CHECK_INTERVAL = Duration.ofMillis(500);

  private static final int TIMERS_PER_BATCH = 100;
  private static final Duration SAVE_MARGIN = Duration.ofSeconds(1);

  static final RetryPolicy BLOCKED_RETRIES =
      new RetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1), Duration.ofMinutes(5), List.of());

  private static final Logger log = LoggerFactory.getLogger(Worker.class);

  public record Settings(
      String owner,
      Duration lease,
      Duration pollInterval,
      int workflowConcurrency,
      int activityConcurrency,
      Set<String> activityQueues) {

    public Settings(
        String owner,
        Duration lease,
        Duration pollInterval,
        int workflowConcurrency,
        int activityConcurrency) {
      this(
          owner,
          lease,
          pollInterval,
          workflowConcurrency,
          activityConcurrency,
          Set.of(DEFAULT_QUEUE));
    }

    public Settings {
      activityQueues = Set.copyOf(activityQueues);
      if (activityQueues.isEmpty()) {
        throw new IllegalArgumentException("an engine must serve at least one activity queue");
      }
      if (workflowConcurrency < 1 || activityConcurrency < 1) {
        throw new IllegalArgumentException(
            "concurrency must be at least 1, was "
                + workflowConcurrency
                + " for workflows and "
                + activityConcurrency
                + " for activities");
      }
    }
  }

  private final JdbcWorkflowStore store;
  private final WorkflowRegistry workflows;
  private final WorkflowRuntime workflowRuntime;
  private final ActivityRuntime activityRuntime;
  private final Settings settings;
  private final String owner;
  private final Duration lease;
  private final CrashPoints crashPoints;
  private final DatabaseHealth health;
  private final Semaphore workflowPermits;
  private final Semaphore activityPermits;
  private final Semaphore workflowWakeups = new Semaphore(0);
  private final Semaphore activityWakeups = new Semaphore(0);
  private final Set<Thread> inFlight = ConcurrentHashMap.newKeySet();
  private final Map<Long, Long> leaseExtendedAt = new ConcurrentHashMap<>();

  private volatile boolean running;
  private List<Thread> pollers = List.of();

  public Worker(
      JdbcWorkflowStore store,
      WorkflowRegistry workflows,
      WorkflowRuntime workflowRuntime,
      ActivityRuntime activityRuntime,
      Settings settings,
      CrashPoints crashPoints,
      DatabaseHealth health) {
    this.health = health;
    this.store = store;
    this.workflows = workflows;
    this.workflowRuntime = workflowRuntime;
    this.activityRuntime = activityRuntime;
    this.settings = settings;
    this.owner = settings.owner();
    this.lease = settings.lease();
    this.crashPoints = crashPoints;
    this.workflowPermits = new Semaphore(settings.workflowConcurrency());
    this.activityPermits = new Semaphore(settings.activityConcurrency());
  }

  public synchronized void start() {
    if (running) {
      throw new IllegalStateException("worker " + owner + " is already running");
    }
    running = true;
    pollers =
        List.of(
            Thread.ofVirtual()
                .name(owner + "-decisions")
                .start(() -> poll(TaskKind.WORKFLOW, workflowPermits, workflowWakeups)),
            Thread.ofVirtual()
                .name(owner + "-activities")
                .start(() -> poll(TaskKind.ACTIVITY, activityPermits, activityWakeups)),
            Thread.ofVirtual().name(owner + "-timers").start(this::fireTimers));
    log.info(
        "worker {} started with {} decision and {} activity slots",
        owner,
        settings.workflowConcurrency(),
        settings.activityConcurrency());
  }

  public synchronized void stop(Duration grace) throws InterruptedException {
    if (!running) {
      return;
    }
    running = false;
    wake(TaskKind.WORKFLOW);
    wake(TaskKind.ACTIVITY);
    Duration pollerExit = settings.pollInterval().plus(TIMER_CHECK_INTERVAL).multipliedBy(2);
    for (Thread poller : pollers) {
      if (!poller.join(pollerExit)) {
        poller.interrupt();
        poller.join();
      }
    }
    long deadline = System.nanoTime() + grace.toNanos();
    for (Thread task : Set.copyOf(inFlight)) {
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) {
        task.join(Duration.ofNanos(remaining));
      }
    }
    Set<Thread> unfinished = Set.copyOf(inFlight);
    if (!unfinished.isEmpty()) {
      releaseLeases(unfinished.size());
      unfinished.forEach(Thread::interrupt);
    }
    log.info("worker {} stopped", owner);
  }

  public String owner() {
    return owner;
  }

  private void releaseLeases(int unfinished) {
    try {
      int released = store.releaseLeases(owner);
      log.info(
          "worker {} stopped {} unfinished tasks after its grace period and released {} leases"
              + " so that other engines take them over now",
          owner,
          unfinished,
          released);
    } catch (StoreException e) {
      log.warn(
          "worker {} could not release its leases; other engines take its tasks over when the"
              + " leases expire: {}",
          owner,
          e.toString());
    }
  }

  public void wake(TaskKind kind, String queue) {
    if (kind == TaskKind.ACTIVITY && !settings.activityQueues().contains(queue)) {
      return;
    }
    wake(kind);
  }

  private void wake(TaskKind kind) {
    Semaphore wakeups = kind == TaskKind.WORKFLOW ? workflowWakeups : activityWakeups;
    if (wakeups.availablePermits() == 0) {
      wakeups.release();
    }
  }

  private void poll(TaskKind kind, Semaphore permits, Semaphore wakeups) {
    int failures = 0;
    while (running) {
      int granted = 0;
      try {
        if (!permits.tryAcquire(settings.pollInterval().toNanos(), TimeUnit.NANOSECONDS)) {
          continue;
        }
        granted = 1 + permits.drainPermits();
        wakeups.drainPermits();
        Set<String> queues =
            kind == TaskKind.WORKFLOW ? Set.of(DEFAULT_QUEUE) : settings.activityQueues();
        List<ClaimedTask> claimed = store.claim(kind, queues, owner, lease, granted);
        health.succeeded();
        failures = 0;
        permits.release(granted - claimed.size());
        granted = 0;
        for (ClaimedTask task : claimed) {
          startTask(kind, task, permits);
        }
        if (claimed.isEmpty()) {
          waitForWork(wakeups);
        }
      } catch (InterruptedException e) {
        permits.release(granted);
        Thread.currentThread().interrupt();
        return;
      } catch (StoreException e) {
        permits.release(granted);
        if (!running) {
          return;
        }
        health.failed(e);
        failures++;
        try {
          Thread.sleep(health.backoff(failures));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      } catch (RuntimeException e) {
        permits.release(granted);
        if (!running) {
          return;
        }
        log.error("worker {} could not claim {} tasks", owner, kind, e);
        try {
          Thread.sleep(settings.pollInterval());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void waitForWork(Semaphore wakeups) throws InterruptedException {
    if (wakeups.tryAcquire(settings.pollInterval().toNanos(), TimeUnit.NANOSECONDS)) {
      log.trace("worker {} was woken by a notification", owner);
    }
  }

  private void startTask(TaskKind kind, ClaimedTask task, Semaphore permits) {
    Thread thread =
        Thread.ofVirtual()
            .name(owner + "-" + kind.name().toLowerCase(Locale.ROOT) + "-" + task.id())
            .unstarted(
                () -> {
                  try {
                    if (kind == TaskKind.WORKFLOW) {
                      decide(task);
                    } else {
                      runActivity(task);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } catch (StoreException e) {
                    health.failed(e);
                  } catch (RuntimeException e) {
                    log.error(
                        "worker {} hit an unexpected error on {} task {} of workflow {}",
                        owner,
                        kind,
                        task.id(),
                        task.workflowId(),
                        e);
                  } finally {
                    inFlight.remove(Thread.currentThread());
                    permits.release();
                  }
                });
    inFlight.add(thread);
    thread.start();
  }

  private void fireTimers() {
    int failures = 0;
    while (running) {
      try {
        int fired = store.fireDueTimers(TIMERS_PER_BATCH);
        health.succeeded();
        failures = 0;
        if (fired < TIMERS_PER_BATCH) {
          Thread.sleep(TIMER_CHECK_INTERVAL);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (StoreException e) {
        if (!running) {
          return;
        }
        health.failed(e);
        failures++;
        try {
          Thread.sleep(health.backoff(failures));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      } catch (RuntimeException e) {
        if (!running) {
          return;
        }
        log.error("worker {} hit an unexpected error while firing timers", owner, e);
        try {
          Thread.sleep(TIMER_CHECK_INTERVAL);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void decide(ClaimedTask task) {
    try {
      DecisionInput input = store.loadDecision(task);
      Optional<WorkflowDefinition<?, ?>> definition = workflows.find(input.workflowType());
      if (definition.isEmpty()) {
        block(
            task,
            new IllegalStateException(
                "workflow type " + input.workflowType() + " is not registered with this engine"));
        return;
      }
      List<Command> commands =
          workflowRuntime.decide(definition.get(), task.workflowId(), input.historyWithInbox());
      store.commitDecision(task, input, commands);
    } catch (NonDeterminismException e) {
      block(task, e);
    } catch (WorkflowBlockedException e) {
      block(task, e.getCause());
    } catch (StaleTaskException e) {
      log.warn("discarded a decision for workflow {}: {}", task.workflowId(), e.getMessage());
    }
  }

  private void block(ClaimedTask task, Throwable error) {
    Duration retryAfter =
        Backoff.delayAfter(BLOCKED_RETRIES, task.attempt(), ThreadLocalRandom.current());
    try {
      store.blockDecision(task, error, retryAfter);
    } catch (StaleTaskException e) {
      log.warn(
          "discarded a blocked decision for workflow {}: {}", task.workflowId(), e.getMessage());
      return;
    }
    if (task.attempt() == 1) {
      log.error(
          "workflow {} is BLOCKED and will be retried in {} until the cause is fixed",
          task.workflowId(),
          retryAfter,
          error);
    } else {
      log.warn(
          "workflow {} is still BLOCKED after {} attempts ({}); retrying in {}",
          task.workflowId(),
          task.attempt(),
          error.toString(),
          retryAfter);
    }
  }

  private boolean extendLease(ClaimedTask task, Duration attemptLease) {
    try {
      store.extendLease(task, attemptLease);
      leaseExtendedAt.put(task.id(), System.nanoTime());
      return true;
    } catch (StaleTaskException e) {
      return false;
    }
  }

  private void runActivity(ClaimedTask task) throws InterruptedException {
    ActivityToRun activity = store.loadActivity(task);
    ActivityOptions options = activity.options();
    try {
      Duration attemptLease =
          options.heartbeatTimeout().orElse(options.attemptTimeout().plus(lease));
      store.extendLease(task, attemptLease);
      leaseExtendedAt.put(task.id(), System.nanoTime());
      ActivityResult result =
          activityRuntime.execute(
              activity.activity(),
              activity.arguments(),
              activity.workflowId(),
              activity.scheduledSeq(),
              options,
              () -> extendLease(task, attemptLease));
      long saveBefore =
          leaseExtendedAt.getOrDefault(task.id(), System.nanoTime())
              + attemptLease.minus(SAVE_MARGIN).toNanos();
      crashPoints.reached(CrashPoint.AFTER_ACTIVITY_RAN);
      if (result instanceof Failed failed
          && failed.retryable()
          && task.attempt() < options.retryPolicy().maxAttempts()) {
        Duration delay =
            Backoff.delayAfter(options.retryPolicy(), task.attempt(), ThreadLocalRandom.current());
        boolean retried =
            saveBefore(
                saveBefore,
                () -> store.retryActivity(task, failed.failure(), delay, options.totalTimeout()));
        log.info(
            retried
                ? "attempt {} of {} for workflow {} failed with {}; retrying in {}"
                : "attempt {} of {} for workflow {} failed with {}; out of time, not retrying",
            task.attempt(),
            activity.activity(),
            task.workflowId(),
            failed.failure().type(),
            delay);
      } else {
        saveBefore(
            saveBefore,
            () -> {
              store.completeActivity(task, result);
              return true;
            });
      }
    } catch (StaleTaskException e) {
      log.warn(
          "discarded the result of {} for workflow {}: {}",
          activity.activity(),
          task.workflowId(),
          e.getMessage());
    } catch (StoreException e) {
      log.warn(
          "could not save the outcome of {} for workflow {} before its lease ended; it will run"
              + " again with the same idempotency key",
          activity.activity(),
          task.workflowId());
    } finally {
      leaseExtendedAt.remove(task.id());
    }
  }

  private <T> T saveBefore(long deadlineNanos, Supplier<T> save) throws InterruptedException {
    int failures = 0;
    while (true) {
      try {
        T saved = save.get();
        health.succeeded();
        return saved;
      } catch (StoreException e) {
        health.failed(e);
        failures++;
        Duration wait = health.backoff(failures);
        if (System.nanoTime() + wait.toNanos() > deadlineNanos) {
          throw e;
        }
        Thread.sleep(wait);
      }
    }
  }
}
