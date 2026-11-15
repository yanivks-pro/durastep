package io.github.yanivks_pro.durastep.api;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * The workflow's view of the engine, passed to {@link Workflow#run}.
 *
 * <p>Every method returns the same values when the workflow is replayed, so workflow code stays
 * deterministic.
 */
public interface WorkflowContext {

  /**
   * Returns a stub for an activity interface, using {@link ActivityOptions#DEFAULT}. Calling a
   * method on the stub runs the activity durably: it executes once its turn comes, and on replay
   * the stub returns the saved result without executing it again. When an activity fails for good,
   * the call throws {@link ActivityFailedException}.
   *
   * @param activityInterface the interface that declares the activities
   * @param <T> the interface type
   * @return a stub implementing {@code activityInterface}
   * @throws IllegalArgumentException if {@code activityInterface} is not an interface
   */
  <T> T activities(Class<T> activityInterface);

  /**
   * Returns a stub for an activity interface whose activities use the given timeouts and retries.
   * When an activity fails for good, the call throws {@link ActivityFailedException}.
   *
   * @param activityInterface the interface that declares the activities
   * @param options the timeouts and retry policy for these activities
   * @param <T> the interface type
   * @return a stub implementing {@code activityInterface}
   * @throws IllegalArgumentException if {@code activityInterface} is not an interface
   */
  <T> T activities(Class<T> activityInterface, ActivityOptions options);

  /**
   * Returns the workflow's current time: the database time at which the workflow reached its
   * current step. It is the same on every replay.
   *
   * @return the workflow's current time
   */
  Instant now();

  /**
   * Returns a random number generator that produces the same sequence on every replay of this
   * workflow.
   *
   * @return the workflow's random number generator
   */
  Random random();

  /**
   * Returns a random UUID that is the same on every replay of this workflow.
   *
   * @return a version 4 UUID drawn from {@link #random()}
   */
  UUID randomUUID();
}
