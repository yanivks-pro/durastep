package io.github.yanivks_pro.durastep.spring;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.time.Duration;
import java.util.List;

final class CompositeEngineListener implements EngineListener {

  private final List<EngineListener> listeners;

  private CompositeEngineListener(List<EngineListener> listeners) {
    this.listeners = List.copyOf(listeners);
  }

  static EngineListener of(List<EngineListener> listeners) {
    return switch (listeners.size()) {
      case 0 -> EngineListener.NONE;
      case 1 -> listeners.getFirst();
      default -> new CompositeEngineListener(listeners);
    };
  }

  @Override
  public void workflowStarted(String type) {
    listeners.forEach(listener -> listener.workflowStarted(type));
  }

  @Override
  public void workflowFinished(String type, WorkflowState state) {
    listeners.forEach(listener -> listener.workflowFinished(type, state));
  }

  @Override
  public void decisionMade(String type, Duration took) {
    listeners.forEach(listener -> listener.decisionMade(type, took));
  }

  @Override
  public void activityFinished(String activity, ActivityOutcome outcome, Duration took) {
    listeners.forEach(listener -> listener.activityFinished(activity, outcome, took));
  }

  @Override
  public void timerFired(Duration late) {
    listeners.forEach(listener -> listener.timerFired(late));
  }

  @Override
  public void leaseExpired(WorkKind kind) {
    listeners.forEach(listener -> listener.leaseExpired(kind));
  }

  @Override
  public void staleResultRejected(WorkKind kind) {
    listeners.forEach(listener -> listener.staleResultRejected(kind));
  }
}
