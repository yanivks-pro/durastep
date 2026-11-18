package io.github.yanivks_pro.durastep.api;

import java.time.Instant;
import java.util.Map;

/**
 * Connects the work of a workflow to the request that started it, in a tracing system such as
 * OpenTelemetry. {@code durastep-opentelemetry} provides one; the core has no tracing dependency.
 *
 * <p>When a workflow starts, the client calls {@link #capture} and stores the returned context with
 * the workflow. Every later call, on any engine and at any time, receives that context in its
 * {@link TraceTarget}, so all of a workflow's activity attempts, timers, signals and its end belong
 * to one trace.
 *
 * <p>Tracing never affects a workflow: errors thrown by an implementation are logged and ignored.
 * Timer, signal and end calls happen after the engine has recorded the event; a crash right after
 * recording can lose the call, never the event.
 *
 * <pre>{@code
 * DurastepEngine.builder().tracing(DurastepTracing.of(openTelemetry))...
 * DurastepClient.connect(dataSource).withTracing(DurastepTracing.of(openTelemetry));
 * }</pre>
 */
public interface WorkflowTracing {

  /** Tracing that does nothing and stores nothing. */
  WorkflowTracing NONE =
      new WorkflowTracing() {
        @Override
        public Map<String, String> capture(String workflowId, String workflowType) {
          return Map.of();
        }

        @Override
        public ActivityTrace activityStarted(TraceTarget target) {
          return ActivityTrace.NONE;
        }
      };

  /**
   * Captures the trace context of the code that starts a workflow, to be stored with it.
   *
   * @param workflowId the workflow's ID
   * @param workflowType the workflow's type name
   * @return the context as text fields, such as {@code traceparent}; empty to store nothing
   */
  Map<String, String> capture(String workflowId, String workflowType);

  /**
   * Called when an engine starts an attempt of an activity.
   *
   * @param target the workflow, the activity's name and the attempt number
   * @return the trace of the attempt
   */
  ActivityTrace activityStarted(TraceTarget target);

  /**
   * Called after a durable timer has fired and the workflow has seen it.
   *
   * @param target the workflow and the timer, such as {@code sleep PT2H}
   * @param startedAt when the timer was started
   * @param firedAt when it fired
   */
  default void timerFired(TraceTarget target, Instant startedAt, Instant firedAt) {}

  /**
   * Called after the workflow has seen a signal.
   *
   * @param target the workflow and the signal's name
   * @param receivedAt when the signal arrived
   */
  default void signalReceived(TraceTarget target, Instant receivedAt) {}

  /**
   * Called after a workflow has finished.
   *
   * @param target the workflow
   * @param state how it finished
   * @param startedAt when it started
   * @param closedAt when it finished
   */
  default void workflowFinished(
      TraceTarget target, WorkflowState state, Instant startedAt, Instant closedAt) {}
}
