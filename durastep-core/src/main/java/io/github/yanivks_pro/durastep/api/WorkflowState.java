package io.github.yanivks_pro.durastep.api;

/**
 * The lifecycle state of a workflow.
 *
 * <p>A workflow is always in exactly one state. {@link #COMPLETED}, {@link #FAILED} and {@link
 * #CANCELLED} are <em>final</em>: once a workflow reaches one of them, its state never changes
 * again.
 */
public enum WorkflowState {
  /** In progress, including while waiting on an activity, a timer or a signal. */
  RUNNING,
  /** Finished successfully with a result. */
  COMPLETED,
  /** Gave up because of an unrecoverable business failure. */
  FAILED,
  /** Stopped on purpose, either cooperatively or by an operator. */
  CANCELLED;

  /**
   * Returns whether this state can never change again.
   *
   * @return {@code true} for COMPLETED, FAILED and CANCELLED
   */
  public boolean isFinal() {
    return this != RUNNING;
  }
}
