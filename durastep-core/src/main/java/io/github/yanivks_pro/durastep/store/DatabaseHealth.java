package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.activity.Backoff;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseHealth {

  private static final Logger log = LoggerFactory.getLogger(DatabaseHealth.class);

  private static final RetryPolicy BACKOFF =
      new RetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1), Duration.ofSeconds(30), List.of());

  private int consecutiveFailures;
  private long unreachableSinceNanos;

  public synchronized void failed(Throwable error) {
    if (consecutiveFailures == 0) {
      unreachableSinceNanos = System.nanoTime();
      log.warn(
          "cannot reach the database; work pauses and is retried with backoff until it is back: {}",
          error.toString());
    }
    consecutiveFailures++;
  }

  public synchronized void succeeded() {
    if (consecutiveFailures > 0) {
      log.info(
          "the database is reachable again after {}; work continues",
          Duration.ofNanos(System.nanoTime() - unreachableSinceNanos)
              .truncatedTo(ChronoUnit.SECONDS));
      consecutiveFailures = 0;
    }
  }

  public synchronized boolean reachable() {
    return consecutiveFailures == 0;
  }

  public Duration backoff(int attempt) {
    return Backoff.delayAfter(BACKOFF, Math.max(1, attempt), ThreadLocalRandom.current());
  }
}
