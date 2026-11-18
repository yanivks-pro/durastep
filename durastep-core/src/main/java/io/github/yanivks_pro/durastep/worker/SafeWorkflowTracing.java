package io.github.yanivks_pro.durastep.worker;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.ActivityTrace;
import io.github.yanivks_pro.durastep.api.TraceTarget;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.api.WorkflowTracing;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafeWorkflowTracing implements WorkflowTracing {

  private static final Logger log = LoggerFactory.getLogger(SafeWorkflowTracing.class);

  private final WorkflowTracing tracing;
  private final AtomicBoolean warned = new AtomicBoolean();

  private SafeWorkflowTracing(WorkflowTracing tracing) {
    this.tracing = tracing;
  }

  public static WorkflowTracing around(WorkflowTracing tracing) {
    return WorkflowTracing.NONE.equals(tracing) || tracing instanceof SafeWorkflowTracing
        ? tracing
        : new SafeWorkflowTracing(tracing);
  }

  @Override
  public Map<String, String> capture(String workflowId, String workflowType) {
    Map<String, String> context = get(() -> tracing.capture(workflowId, workflowType), Map.of());
    return context == null ? Map.of() : context;
  }

  @Override
  public ActivityTrace activityStarted(TraceTarget target) {
    ActivityTrace trace = get(() -> tracing.activityStarted(target), ActivityTrace.NONE);
    return trace == null ? ActivityTrace.NONE : new SafeActivityTrace(trace);
  }

  @Override
  public void timerFired(TraceTarget target, Instant startedAt, Instant firedAt) {
    run(() -> tracing.timerFired(target, startedAt, firedAt));
  }

  @Override
  public void signalReceived(TraceTarget target, Instant receivedAt) {
    run(() -> tracing.signalReceived(target, receivedAt));
  }

  @Override
  public void workflowFinished(
      TraceTarget target, WorkflowState state, Instant startedAt, Instant closedAt) {
    run(() -> tracing.workflowFinished(target, state, startedAt, closedAt));
  }

  private final class SafeActivityTrace implements ActivityTrace {
    private final ActivityTrace trace;

    SafeActivityTrace(ActivityTrace trace) {
      this.trace = trace;
    }

    @Override
    public <T> Callable<T> wrap(Callable<T> attempt) {
      Callable<T> wrapped = get(() -> trace.wrap(attempt), attempt);
      return wrapped == null ? attempt : wrapped;
    }

    @Override
    public void end(ActivityOutcome outcome, String failureType) {
      run(() -> trace.end(outcome, failureType));
    }
  }

  private void run(Runnable call) {
    get(
        () -> {
          call.run();
          return null;
        },
        null);
  }

  private <T> T get(Supplier<T> call, T fallback) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      if (warned.compareAndSet(false, true)) {
        log.warn("the workflow tracing failed; workflows are not affected", e);
      } else {
        log.debug("the workflow tracing failed again", e);
      }
      return fallback;
    }
  }
}
