package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Timeouts and retries for the activities obtained with {@link WorkflowContext#activities(Class,
 * ActivityOptions)}.
 *
 * <pre>{@code
 * ActivityOptions.builder()
 *     .attemptTimeout(Duration.ofSeconds(10))
 *     .totalTimeout(Duration.ofMinutes(5))
 *     .retryPolicy(RetryPolicy.builder().maxAttempts(3).build())
 *     .build();
 * }</pre>
 */
public final class ActivityOptions {

  /**
   * The defaults: a 1 minute limit per attempt, no total limit, and {@link RetryPolicy#DEFAULT}.
   */
  public static final ActivityOptions DEFAULT = builder().build();

  private final Duration attemptTimeout;
  private final Duration totalTimeout;
  private final Duration heartbeatTimeout;
  private final String queue;
  private final RetryPolicy retryPolicy;

  private ActivityOptions(Builder builder) {
    this.attemptTimeout = requirePositive(builder.attemptTimeout, "attemptTimeout");
    this.totalTimeout =
        builder.totalTimeout == null ? null : requirePositive(builder.totalTimeout, "totalTimeout");
    this.heartbeatTimeout =
        builder.heartbeatTimeout == null
            ? null
            : requirePositive(builder.heartbeatTimeout, "heartbeatTimeout");
    this.retryPolicy = Objects.requireNonNull(builder.retryPolicy, "retryPolicy");
    this.queue = builder.queue;
  }

  /**
   * Returns a builder that starts from the defaults.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns how long one attempt may run before it is stopped and counted as a retryable failure.
   *
   * @return the limit per attempt
   */
  public Duration attemptTimeout() {
    return attemptTimeout;
  }

  /**
   * Returns how long all attempts together may take, measured from when the activity was scheduled,
   * if a limit is set.
   *
   * @return the total limit, or empty for no limit
   */
  public Optional<Duration> totalTimeout() {
    return Optional.ofNullable(totalTimeout);
  }

  /**
   * Returns how long an attempt may go without calling {@link ActivityContext#heartbeat()}, if set.
   * While an attempt heartbeats, no other engine takes it over; if its engine crashes, the attempt
   * is taken over once this time has passed, instead of after the whole attempt timeout.
   *
   * @return the heartbeat timeout, or empty if the activity does not heartbeat
   */
  public Optional<Duration> heartbeatTimeout() {
    return Optional.ofNullable(heartbeatTimeout);
  }

  /**
   * Returns the queue these activities are sent to. Only engines that serve this queue, set with
   * {@link DurastepEngine.Builder#activityQueues}, run them.
   *
   * @return the queue name, {@code default} unless set
   */
  public String queue() {
    return queue;
  }

  /**
   * Returns the retry policy.
   *
   * @return the retry policy
   */
  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ActivityOptions that
        && attemptTimeout.equals(that.attemptTimeout)
        && Objects.equals(totalTimeout, that.totalTimeout)
        && Objects.equals(heartbeatTimeout, that.heartbeatTimeout)
        && queue.equals(that.queue)
        && retryPolicy.equals(that.retryPolicy);
  }

  @Override
  public int hashCode() {
    return Objects.hash(attemptTimeout, totalTimeout, heartbeatTimeout, retryPolicy, queue);
  }

  @Override
  public String toString() {
    return "ActivityOptions[attemptTimeout="
        + attemptTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", heartbeatTimeout="
        + heartbeatTimeout
        + ", retryPolicy="
        + retryPolicy
        + ", queue="
        + queue
        + "]";
  }

  static String requireQueueName(String queue) {
    Objects.requireNonNull(queue, "queue");
    if (!queue.matches("[A-Za-z0-9._-]{1,100}")) {
      throw new IllegalArgumentException(
          "a queue name must have 1 to 100 letters, digits, dots, dashes or underscores, was '"
              + queue
              + "'");
    }
    return queue;
  }

  private static Duration requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive, was " + duration);
    }
    return duration;
  }

  /** Builds {@link ActivityOptions}, starting from the defaults. */
  public static final class Builder {

    private Duration attemptTimeout = Duration.ofMinutes(1);
    private Duration totalTimeout;
    private Duration heartbeatTimeout;
    private String queue = "default";
    private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

    private Builder() {}

    /**
     * Sets how long one attempt may run. Defaults to 1 minute.
     *
     * @param attemptTimeout a positive duration
     * @return this builder
     */
    public Builder attemptTimeout(Duration attemptTimeout) {
      this.attemptTimeout = attemptTimeout;
      return this;
    }

    /**
     * Sets how long all attempts together may take. No limit by default.
     *
     * @param totalTimeout a positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration totalTimeout) {
      this.totalTimeout = Objects.requireNonNull(totalTimeout, "totalTimeout");
      return this;
    }

    /**
     * Sets how long an attempt may go without a heartbeat. Use it for long activities that call
     * {@link ActivityContext#heartbeat()} regularly, so a crashed engine's attempt is taken over
     * quickly. Not set by default.
     *
     * @param heartbeatTimeout a positive duration
     * @return this builder
     */
    public Builder heartbeatTimeout(Duration heartbeatTimeout) {
      this.heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
      return this;
    }

    /**
     * Sends these activities to a named queue, so that only engines serving that queue run them,
     * for example machines with a GPU. Defaults to {@code default}.
     *
     * @param queue a name of 1 to 100 letters, digits, dots, dashes or underscores
     * @return this builder
     * @throws IllegalArgumentException if the name is not valid
     */
    public Builder queue(String queue) {
      this.queue = requireQueueName(queue);
      return this;
    }

    /**
     * Sets the retry policy. Defaults to {@link RetryPolicy#DEFAULT}.
     *
     * @param retryPolicy the retry policy
     * @return this builder
     */
    public Builder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     * @throws IllegalArgumentException if a timeout is not positive
     */
    public ActivityOptions build() {
      return new ActivityOptions(this);
    }
  }
}
