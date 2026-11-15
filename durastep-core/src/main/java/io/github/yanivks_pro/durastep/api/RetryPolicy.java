package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How often, and how patiently, a failed activity is tried again.
 *
 * <p>After attempt {@code n} fails, the next attempt waits {@code initialBackoff × 2^(n−1)}, capped
 * at {@code maxBackoff}, and then a random amount is taken off so that many failing activities do
 * not retry at the same moment: the actual wait is between 50% and 100% of that value.
 *
 * <pre>{@code
 * RetryPolicy.builder()
 *     .maxAttempts(5)
 *     .initialBackoff(Duration.ofSeconds(1))
 *     .maxBackoff(Duration.ofMinutes(1))
 *     .nonRetryable(CardDeclinedException.class)
 *     .build();
 * }</pre>
 *
 * @param maxAttempts the total number of attempts, including the first; at least 1
 * @param initialBackoff the wait before the second attempt, before jitter
 * @param maxBackoff the longest wait between two attempts, before jitter
 * @param nonRetryableTypes class names of errors that fail the activity at once, matched against
 *     the thrown error's class and all its superclasses and interfaces
 */
public record RetryPolicy(
    int maxAttempts, Duration initialBackoff, Duration maxBackoff, List<String> nonRetryableTypes) {

  /** The default policy: 5 attempts, waiting 1 second at first and doubling up to 1 minute. */
  public static final RetryPolicy DEFAULT = builder().build();

  /**
   * Validates the policy.
   *
   * @throws IllegalArgumentException if {@code maxAttempts} is below 1, a backoff is not positive,
   *     or {@code maxBackoff} is shorter than {@code initialBackoff}
   */
  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
    }
    requirePositive(initialBackoff, "initialBackoff");
    requirePositive(maxBackoff, "maxBackoff");
    if (maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException(
          "maxBackoff " + maxBackoff + " is shorter than initialBackoff " + initialBackoff);
    }
    nonRetryableTypes = List.copyOf(nonRetryableTypes);
  }

  /**
   * Returns a policy that never retries: the first failure is final.
   *
   * @return a policy with one attempt
   */
  public static RetryPolicy none() {
    return builder().maxAttempts(1).build();
  }

  /**
   * Returns a builder that starts from the default policy.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns whether an error may be retried under this policy.
   *
   * @param error the error an attempt failed with
   * @return {@code false} if the error, or one of its superclasses or interfaces, is listed as
   *     non-retryable
   */
  public boolean isRetryable(Throwable error) {
    return isRetryable(error.getClass());
  }

  private boolean isRetryable(Class<?> type) {
    if (nonRetryableTypes.contains(type.getName())) {
      return false;
    }
    for (Class<?> implemented : type.getInterfaces()) {
      if (!isRetryable(implemented)) {
        return false;
      }
    }
    Class<?> parent = type.getSuperclass();
    return parent == null || isRetryable(parent);
  }

  private static void requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive, was " + duration);
    }
  }

  /** Builds a {@link RetryPolicy}, starting from the defaults. */
  public static final class Builder {

    private int maxAttempts = 5;
    private Duration initialBackoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofMinutes(1);
    private final List<String> nonRetryableTypes = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the total number of attempts, including the first.
     *
     * @param maxAttempts at least 1
     * @return this builder
     */
    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the wait before the second attempt, before jitter.
     *
     * @param initialBackoff a positive duration
     * @return this builder
     */
    public Builder initialBackoff(Duration initialBackoff) {
      this.initialBackoff = initialBackoff;
      return this;
    }

    /**
     * Sets the longest wait between two attempts, before jitter.
     *
     * @param maxBackoff a positive duration, at least {@code initialBackoff}
     * @return this builder
     */
    public Builder maxBackoff(Duration maxBackoff) {
      this.maxBackoff = maxBackoff;
      return this;
    }

    /**
     * Adds errors that fail the activity at once instead of being retried, such as a declined card.
     * Subclasses of these types are not retried either.
     *
     * @param types the error types
     * @return this builder
     */
    @SafeVarargs
    public final Builder nonRetryable(Class<? extends Throwable>... types) {
      for (Class<? extends Throwable> type : types) {
        nonRetryableTypes.add(type.getName());
      }
      return this;
    }

    /**
     * Builds the policy.
     *
     * @return the policy
     * @throws IllegalArgumentException if the settings are invalid
     */
    public RetryPolicy build() {
      return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, nonRetryableTypes);
    }
  }
}
