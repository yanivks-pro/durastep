package io.github.yanivks_pro.durastep.api;

/**
 * Thrown in workflow code when an activity has failed for good: its error was not retryable, or all
 * attempts allowed by its {@link RetryPolicy} failed.
 *
 * <p>Workflow code may catch it, for example to undo earlier steps. If it is not caught, the
 * workflow ends in {@link WorkflowState#FAILED}.
 *
 * <pre>{@code
 * try {
 *   shop.chargeCard(order.id(), order.cents());
 * } catch (ActivityFailedException e) {
 *   shop.releaseStock(order.id());
 *   throw e;
 * }
 * }</pre>
 */
public final class ActivityFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The activity, such as {@code Payments.charge}. */
  private final String activity;

  /** The class name of the last attempt's error. */
  private final String failureType;

  /** The message of the last attempt's error. */
  private final String failureMessage;

  /** How many attempts were made. */
  private final int attempts;

  /**
   * Creates the exception.
   *
   * @param activity the activity, for example {@code OrderActivities.chargeCard}
   * @param failureType the class name of the error the last attempt failed with
   * @param failureMessage the message of that error
   * @param attempts how many attempts were made
   */
  public ActivityFailedException(
      String activity, String failureType, String failureMessage, int attempts) {
    super(
        activity
            + " failed after "
            + attempts
            + (attempts == 1 ? " attempt: " : " attempts: ")
            + failureType
            + ": "
            + failureMessage);
    this.activity = activity;
    this.failureType = failureType;
    this.failureMessage = failureMessage;
    this.attempts = attempts;
  }

  /**
   * Returns the activity that failed.
   *
   * @return the activity, for example {@code OrderActivities.chargeCard}
   */
  public String activity() {
    return activity;
  }

  /**
   * Returns the class name of the error the last attempt failed with.
   *
   * @return the failure's type, for example {@code com.example.CardDeclinedException}
   */
  public String failureType() {
    return failureType;
  }

  /**
   * Returns the message of the error the last attempt failed with.
   *
   * @return the failure's message
   */
  public String failureMessage() {
    return failureMessage;
  }

  /**
   * Returns how many attempts were made.
   *
   * @return the number of attempts, at least 1
   */
  public int attempts() {
    return attempts;
  }
}
