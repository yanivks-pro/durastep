package io.github.yanivks_pro.durastep.worker;

import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StoreException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Retention {

  public record Settings(
      Duration keepFor, Duration firstRunAfter, Duration interval, int batchSize, int maxBatches) {

    public static Settings keepingFor(Duration keepFor) {
      return new Settings(keepFor, Duration.ofSeconds(2), Duration.ofMinutes(10), 500, 20);
    }

    public Settings {
      if (keepFor.isNegative() || keepFor.isZero()) {
        throw new IllegalArgumentException("retention must be positive, was " + keepFor);
      }
      if (batchSize < 1 || maxBatches < 1) {
        throw new IllegalArgumentException("batch size and batch count must be positive");
      }
    }
  }

  private static final Logger log = LoggerFactory.getLogger(Retention.class);

  private final JdbcWorkflowStore store;
  private final Settings settings;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private Thread thread;

  public Retention(JdbcWorkflowStore store, Settings settings) {
    this.store = store;
    this.settings = settings;
  }

  public void start() {
    thread = Thread.ofVirtual().name("durastep-retention").start(this::runUntilStopped);
  }

  public void stop() throws InterruptedException {
    stopped.countDown();
    if (thread != null) {
      thread.join();
    }
  }

  public int runOnce() {
    int deleted = 0;
    try {
      for (int batch = 0; batch < settings.maxBatches(); batch++) {
        OptionalInt batchDeleted =
            store.deleteFinishedWorkflows(settings.keepFor(), settings.batchSize());
        if (batchDeleted.isEmpty()) {
          break;
        }
        deleted += batchDeleted.getAsInt();
        if (batchDeleted.getAsInt() < settings.batchSize()) {
          break;
        }
      }
    } catch (StoreException e) {
      log.warn("could not delete old finished workflows; will try again later: {}", e.getMessage());
    }
    if (deleted > 0) {
      log.info(
          "deleted {} finished workflows that closed more than {} ago",
          deleted,
          settings.keepFor());
    }
    return deleted;
  }

  private void runUntilStopped() {
    Duration wait = settings.firstRunAfter();
    try {
      while (!stopped.await(wait.toMillis(), TimeUnit.MILLISECONDS)) {
        runOnce();
        wait = settings.interval();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
