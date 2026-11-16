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
import io.github.yanivks_pro.durastep.store.DecisionInput;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StaleTaskException;
import io.github.yanivks_pro.durastep.store.TaskKind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Worker {

  public static final String DEFAULT_QUEUE = "default";
  public static final Duration TIMER_CHECK_INTERVAL = Duration.ofMillis(500);

  private static final int TIMERS_PER_BATCH = 100;

  static final RetryPolicy BLOCKED_RETRIES =
      new RetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1), Duration.ofMinutes(5), List.of());

  private static final Logger log = LoggerFactory.getLogger(Worker.class);

  private final JdbcWorkflowStore store;
  private final WorkflowRegistry workflows;
  private final WorkflowRuntime workflowRuntime;
  private final ActivityRuntime activityRuntime;
  private final String owner;
  private final Duration lease;
  private final Duration pollInterval;
  private final CrashPoints crashPoints;

  private volatile boolean running;
  private Thread decider;
  private Thread activityRunner;
  private Thread timerFirer;

  public Worker(
      JdbcWorkflowStore store,
      WorkflowRegistry workflows,
      WorkflowRuntime workflowRuntime,
      ActivityRuntime activityRuntime,
      String owner,
      Duration lease,
      Duration pollInterval,
      CrashPoints crashPoints) {
    this.store = store;
    this.workflows = workflows;
    this.workflowRuntime = workflowRuntime;
    this.activityRuntime = activityRuntime;
    this.owner = owner;
    this.lease = lease;
    this.pollInterval = pollInterval;
    this.crashPoints = crashPoints;
  }

  public synchronized void start() {
    if (running) {
      throw new IllegalStateException("worker " + owner + " is already running");
    }
    running = true;
    decider = Thread.ofVirtual().name(owner + "-decider").start(() -> loop(TaskKind.WORKFLOW));
    activityRunner =
        Thread.ofVirtual().name(owner + "-activities").start(() -> loop(TaskKind.ACTIVITY));
    timerFirer = Thread.ofVirtual().name(owner + "-timers").start(this::fireTimers);
    log.info("worker {} started", owner);
  }

  public synchronized void stop(Duration grace) throws InterruptedException {
    if (!running) {
      return;
    }
    running = false;
    for (Thread thread : List.of(decider, activityRunner, timerFirer)) {
      if (!thread.join(grace)) {
        thread.interrupt();
        thread.join();
      }
    }
    log.info("worker {} stopped", owner);
  }

  public String owner() {
    return owner;
  }

  private void loop(TaskKind kind) {
    while (running) {
      try {
        boolean worked = kind == TaskKind.WORKFLOW ? decideOnce() : runActivityOnce();
        if (!worked) {
          Thread.sleep(pollInterval);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.error("worker {} hit an unexpected error while processing {} tasks", owner, kind, e);
        try {
          Thread.sleep(pollInterval);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void fireTimers() {
    while (running) {
      try {
        if (store.fireDueTimers(TIMERS_PER_BATCH) < TIMERS_PER_BATCH) {
          Thread.sleep(TIMER_CHECK_INTERVAL);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
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

  private boolean decideOnce() {
    Optional<ClaimedTask> claimed = store.claim(TaskKind.WORKFLOW, DEFAULT_QUEUE, owner, lease);
    if (claimed.isEmpty()) {
      return false;
    }
    ClaimedTask task = claimed.get();
    try {
      DecisionInput input = store.loadDecision(task);
      Optional<WorkflowDefinition<?, ?>> definition = workflows.find(input.workflowType());
      if (definition.isEmpty()) {
        block(
            task,
            new IllegalStateException(
                "workflow type " + input.workflowType() + " is not registered with this engine"));
        return true;
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
    return true;
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

  private boolean runActivityOnce() throws InterruptedException {
    Optional<ClaimedTask> claimed = store.claim(TaskKind.ACTIVITY, DEFAULT_QUEUE, owner, lease);
    if (claimed.isEmpty()) {
      return false;
    }
    ClaimedTask task = claimed.get();
    ActivityToRun activity = store.loadActivity(task);
    ActivityOptions options = activity.options();
    try {
      store.extendLease(task, options.attemptTimeout().plus(lease));
      ActivityResult result =
          activityRuntime.execute(
              activity.activity(),
              activity.arguments(),
              activity.workflowId(),
              activity.scheduledSeq(),
              options);
      crashPoints.reached(CrashPoint.AFTER_ACTIVITY_RAN);
      if (result instanceof Failed failed
          && failed.retryable()
          && task.attempt() < options.retryPolicy().maxAttempts()) {
        Duration delay =
            Backoff.delayAfter(options.retryPolicy(), task.attempt(), ThreadLocalRandom.current());
        boolean retried =
            store.retryActivity(task, failed.failure(), delay, options.totalTimeout());
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
        store.completeActivity(task, result);
      }
    } catch (StaleTaskException e) {
      log.warn(
          "discarded the result of {} for workflow {}: {}",
          activity.activity(),
          task.workflowId(),
          e.getMessage());
    }
    return true;
  }
}
