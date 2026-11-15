package io.github.yanivks_pro.durastep.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.RetryPolicy;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BackoffTest {

  private static final RetryPolicy DEFAULT = RetryPolicy.DEFAULT;

  @Test
  void theBaseDelayDoublesAfterEachFailureUntilTheCap() {
    assertThat(IntStream.rangeClosed(1, 8).mapToObj(n -> Backoff.baseDelayAfter(DEFAULT, n)))
        .containsExactly(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16),
            Duration.ofSeconds(32),
            Duration.ofMinutes(1),
            Duration.ofMinutes(1));
  }

  @Test
  void aVeryLargeAttemptNumberStaysAtTheCapInsteadOfOverflowing() {
    assertThat(Backoff.baseDelayAfter(DEFAULT, 64)).isEqualTo(Duration.ofMinutes(1));
    assertThat(Backoff.baseDelayAfter(DEFAULT, Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void everyDelayIsBetweenHalfAndAllOfTheBaseAndUsesTheWholeRange() {
    SplittableRandom random = new SplittableRandom(42);
    for (int attempt = 1; attempt <= 10; attempt++) {
      long base = Backoff.baseDelayAfter(DEFAULT, attempt).toMillis();
      long shortest = Long.MAX_VALUE;
      long longest = 0;
      for (int sample = 0; sample < 10_000; sample++) {
        long delay = Backoff.delayAfter(DEFAULT, attempt, random).toMillis();
        assertThat(delay).isBetween(base / 2, base);
        shortest = Math.min(shortest, delay);
        longest = Math.max(longest, delay);
      }
      assertThat(shortest).isLessThan(base * 55 / 100);
      assertThat(longest).isGreaterThan(base * 95 / 100);
    }
  }

  @Test
  void aBackoffShorterThanAMillisecondNeverBecomesAnImmediateRetry() {
    RetryPolicy tiny =
        RetryPolicy.builder()
            .initialBackoff(Duration.ofNanos(10))
            .maxBackoff(Duration.ofNanos(20))
            .build();

    assertThat(Backoff.delayAfter(tiny, 1, new SplittableRandom(1)))
        .isEqualTo(Duration.ofMillis(1));
  }

  @Test
  void attemptsAreNumberedFromOne() {
    assertThatThrownBy(() -> Backoff.baseDelayAfter(DEFAULT, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
