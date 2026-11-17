package io.github.yanivks_pro.durastep.api;

/** How an activity attempt ended, as reported to an {@link EngineListener}. */
public enum ActivityOutcome {
  /** The activity returned a result. */
  SUCCEEDED,
  /** The attempt failed or timed out, and the activity will be retried. */
  RETRIED,
  /** The activity failed for good, and the workflow receives an {@link ActivityFailedException}. */
  FAILED,
  /**
   * The last allowed attempt timed out, and the workflow receives an {@link
   * ActivityFailedException}.
   */
  TIMED_OUT
}
