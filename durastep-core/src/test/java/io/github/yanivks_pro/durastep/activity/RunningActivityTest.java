package io.github.yanivks_pro.durastep.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityLeaseLostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunningActivityTest {

  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger beats = new AtomicInteger();

  private RunningActivity activity(boolean leaseStillHeld) {
    return new RunningActivity(
        "order-42",
        2,
        () -> {
          beats.incrementAndGet();
          return leaseStillHeld;
        },
        Duration.ofSeconds(30),
        clock::get);
  }

  @Test
  void onlyOneHeartbeatInEveryThirdOfTheTimeoutReachesTheDatabase() {
    RunningActivity running = activity(true);

    for (int second = 1; second <= 60; second++) {
      clock.set(Duration.ofSeconds(second).toNanos());
      running.heartbeat();
    }

    assertThat(beats.get()).isEqualTo(6);
  }

  @Test
  void aHeartbeatAfterTheAttemptWasTakenOverThrows() {
    RunningActivity running = activity(false);
    clock.set(Duration.ofSeconds(11).toNanos());

    assertThatThrownBy(running::heartbeat)
        .isInstanceOf(ActivityLeaseLostException.class)
        .hasMessageContaining("order-42/activity-2");
  }

  @Test
  void withoutAHeartbeatTimeoutHeartbeatsDoNothing() {
    RunningActivity running = new RunningActivity("order-42", 2);

    running.heartbeat();
    running.heartbeat();

    assertThat(running.idempotencyKey()).isEqualTo("order-42/activity-2");
  }
}
