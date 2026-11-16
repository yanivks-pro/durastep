package io.github.yanivks_pro.durastep.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.activity.Backoff;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BlockedRetriesTest {

  @Test
  void aBlockedWorkflowIsRetriedAfterOneSecondDoublingUpToFiveMinutes() {
    assertThat(
            IntStream.of(1, 2, 3, 9, 10, 1_000)
                .mapToObj(attempt -> Backoff.baseDelayAfter(Worker.BLOCKED_RETRIES, attempt)))
        .containsExactly(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(256),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));
  }

  @Test
  void aBlockedWorkflowIsNeverGivenUpOn() {
    assertThat(Worker.BLOCKED_RETRIES.maxAttempts()).isEqualTo(Integer.MAX_VALUE);
  }
}
