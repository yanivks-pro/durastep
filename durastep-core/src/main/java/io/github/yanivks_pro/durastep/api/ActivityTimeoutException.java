package io.github.yanivks_pro.durastep.api;

import java.time.Duration;

/**
 * The error an activity attempt fails with when it runs longer than its {@link
 * ActivityOptions#attemptTimeout()}. The attempt is interrupted and, unless this type is listed as
 * non-retryable, retried.
 */
public final class ActivityTimeoutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param activity the activity that ran too long
   * @param timeout the limit it exceeded
   */
  public ActivityTimeoutException(String activity, Duration timeout) {
    super(activity + " did not finish within " + timeout);
  }
}
