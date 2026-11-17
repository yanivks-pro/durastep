package io.github.yanivks_pro.durastep.api;

import java.time.Duration;

/**
 * Receives events from an engine, for metrics or tracing; set it with {@link
 * DurastepEngine.Builder#listener(EngineListener)}. Every method does nothing by default, so an
 * implementation overrides only what it needs. The {@code durastep-micrometer} module provides one
 * that records Micrometer meters.
 *
 * <p>Methods are called on the engine's threads and must return quickly. An exception thrown by a
 * listener is logged and otherwise ignored; it never affects a workflow.
 */
public interface EngineListener {

  /** A listener that ignores every event. */
  EngineListener NONE = new EngineListener() {};

  /**
   * A workflow was started by this engine's client.
   *
   * @param type the workflow type name
   */
  default void workflowStarted(String type) {}

  /**
   * A workflow finished on this engine, or was terminated through its client.
   *
   * @param type the workflow type name
   * @param state how it ended: completed, failed or cancelled
   */
  default void workflowFinished(String type, WorkflowState state) {}

  /**
   * A decision was made and saved: the workflow's history was replayed and its next steps stored.
   *
   * @param type the workflow type name
   * @param took how long the decision took, from claiming it to saving it
   */
  default void decisionMade(String type, Duration took) {}

  /**
   * An activity attempt ended and its outcome was saved.
   *
   * @param activity the activity's name, such as {@code Payments.charge}
   * @param outcome how the attempt ended
   * @param took how long the attempt ran
   */
  default void activityFinished(String activity, ActivityOutcome outcome, Duration took) {}

  /**
   * A timer fired, such as the end of a {@link WorkflowContext#sleep}.
   *
   * @param late how long after its due time it fired
   */
  default void timerFired(Duration late) {}

  /**
   * This engine took over work whose lease had expired, because the engine that held it crashed,
   * froze or lost its connection.
   *
   * @param kind the kind of work taken over
   */
  default void leaseExpired(WorkKind kind) {}

  /**
   * This engine's result was rejected because another engine had taken the work over in the
   * meantime; the other engine's result counts.
   *
   * @param kind the kind of work whose result was rejected
   */
  default void staleResultRejected(WorkKind kind) {}
}
