package io.github.yanivks_pro.durastep.worker;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafeEngineListener implements EngineListener {

  private static final Logger log = LoggerFactory.getLogger(SafeEngineListener.class);

  private final EngineListener listener;
  private final AtomicBoolean warned = new AtomicBoolean();

  private SafeEngineListener(EngineListener listener) {
    this.listener = listener;
  }

  public static EngineListener around(EngineListener listener) {
    return EngineListener.NONE.equals(listener) || listener instanceof SafeEngineListener
        ? listener
        : new SafeEngineListener(listener);
  }

  @Override
  public void workflowStarted(String type) {
    call(() -> listener.workflowStarted(type));
  }

  @Override
  public void workflowFinished(String type, WorkflowState state) {
    call(() -> listener.workflowFinished(type, state));
  }

  @Override
  public void decisionMade(String type, Duration took) {
    call(() -> listener.decisionMade(type, took));
  }

  @Override
  public void activityFinished(String activity, ActivityOutcome outcome, Duration took) {
    call(() -> listener.activityFinished(activity, outcome, took));
  }

  @Override
  public void timerFired(Duration late) {
    call(() -> listener.timerFired(late));
  }

  @Override
  public void leaseExpired(WorkKind kind) {
    call(() -> listener.leaseExpired(kind));
  }

  @Override
  public void staleResultRejected(WorkKind kind) {
    call(() -> listener.staleResultRejected(kind));
  }

  private void call(Runnable event) {
    try {
      event.run();
    } catch (RuntimeException e) {
      if (warned.compareAndSet(false, true)) {
        log.warn("the engine listener {} failed; its errors are now ignored", listener, e);
      } else {
        log.debug("the engine listener {} failed", listener, e);
      }
    }
  }
}
