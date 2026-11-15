package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.api.RetryPolicy;
import java.time.Duration;
import java.util.random.RandomGenerator;

public final class Backoff {

  private Backoff() {}

  public static Duration baseDelayAfter(RetryPolicy policy, int failedAttempt) {
    if (failedAttempt < 1) {
      throw new IllegalArgumentException("attempts are numbered from 1, was " + failedAttempt);
    }
    long initial = Math.max(1, policy.initialBackoff().toMillis());
    long max = Math.max(initial, policy.maxBackoff().toMillis());
    int doublings = failedAttempt - 1;
    if (doublings >= Long.numberOfLeadingZeros(initial) - 1) {
      return Duration.ofMillis(max);
    }
    return Duration.ofMillis(Math.min(initial << doublings, max));
  }

  public static Duration delayAfter(RetryPolicy policy, int failedAttempt, RandomGenerator random) {
    long base = baseDelayAfter(policy, failedAttempt).toMillis();
    long floor = base - base / 2;
    return Duration.ofMillis(floor + random.nextLong(base - floor + 1));
  }
}
