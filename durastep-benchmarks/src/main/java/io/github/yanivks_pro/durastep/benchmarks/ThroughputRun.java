package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.ThreeSteps;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

final class ThroughputRun {

  record Result(int workflows, Duration took, double perSecond, EngineSettings settings) {}

  private static final String PREFIX = "drain-";
  private static final Duration LIMIT = Duration.ofMinutes(15);
  private static final int STARTERS = 16;

  private ThroughputRun() {}

  static Result run(DataSource dataSource, int workflows, EngineSettings settings)
      throws Exception {
    Measurements measurements = new Measurements(dataSource);
    measurements.forgetEverything();
    DurastepClient client = DurastepClient.connect(dataSource);
    AtomicLong failed = new AtomicLong();
    try (ExecutorService starters =
        Executors.newFixedThreadPool(STARTERS, Thread.ofVirtual().factory())) {
      for (int i = 0; i < workflows; i++) {
        String id = PREFIX + i;
        starters.execute(
            () -> {
              try {
                client.start(ThreeSteps.class, id, id);
              } catch (RuntimeException e) {
                failed.incrementAndGet();
              }
            });
      }
    }
    if (failed.get() > 0) {
      throw new IllegalStateException(failed.get() + " workflows could not be started");
    }
    Instant started = measurements.now();
    try (DurastepEngine engine = settings.engine(dataSource)) {
      engine.start();
      waitUntilCompleted(measurements, workflows);
    }
    Duration took = Duration.between(started, measurements.lastClosed(PREFIX));
    return new Result(workflows, took, workflows * 1_000.0 / took.toMillis(), settings);
  }

  private static void waitUntilCompleted(Measurements measurements, int workflows)
      throws SQLException, InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + LIMIT.toNanos();
    while (measurements.completed(PREFIX) < workflows) {
      if (System.nanoTime() > deadline) {
        throw new TimeoutException("the drain did not finish within " + LIMIT);
      }
      Thread.sleep(Duration.ofMillis(100));
    }
  }
}
