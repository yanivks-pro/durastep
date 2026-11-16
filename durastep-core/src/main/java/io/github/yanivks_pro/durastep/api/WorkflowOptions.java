package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Options for starting a workflow with {@link DurastepClient#start(Class, String, Object,
 * WorkflowOptions)}.
 *
 * <pre>{@code
 * client.start(OrderWorkflow.class, "order-42", order, WorkflowOptions.timeout(Duration.ofHours(1)));
 * }</pre>
 */
public final class WorkflowOptions {

  /** No options: the workflow may run for as long as it needs. */
  public static final WorkflowOptions DEFAULT = new WorkflowOptions(null);

  private final Duration timeout;

  private WorkflowOptions(Duration timeout) {
    this.timeout = timeout;
  }

  /**
   * Returns options with a deadline for the whole workflow. If the workflow has not finished when
   * the deadline passes, it ends in {@link WorkflowState#FAILED} with a {@link
   * WorkflowTimeoutException}, about a second after the deadline.
   *
   * @param timeout how long the workflow may run, measured from when it starts
   * @return the options
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public static WorkflowOptions timeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, was " + timeout);
    }
    return new WorkflowOptions(timeout);
  }

  /**
   * Returns the deadline for the whole workflow, if one is set.
   *
   * @return the timeout, or empty for no deadline
   */
  public Optional<Duration> timeout() {
    return Optional.ofNullable(timeout);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WorkflowOptions that && Objects.equals(timeout, that.timeout);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(timeout);
  }

  @Override
  public String toString() {
    return "WorkflowOptions[timeout=" + timeout + "]";
  }
}
