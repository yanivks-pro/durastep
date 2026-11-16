package io.github.yanivks_pro.durastep.api;

/**
 * Thrown by {@link ActivityContext#heartbeat()} when another engine has taken the attempt over,
 * usually because this one stopped heartbeating for longer than its heartbeat timeout, for example
 * while its process was paused. The attempt should stop: its result would be discarded.
 */
public final class ActivityLeaseLostException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param idempotencyKey the key of the attempt that lost its lease
   */
  public ActivityLeaseLostException(String idempotencyKey) {
    super("activity " + idempotencyKey + " was taken over by another engine");
  }
}
