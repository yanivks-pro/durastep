package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.Sleeper;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.ThreeSteps;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

final class LatencyRun {

  record Result(
      double ratePerSecond,
      Duration measured,
      long late,
      Percentiles stepCycles,
      Percentiles activityHops,
      Percentiles workflows,
      Percentiles timerLateness,
      EngineSettings settings) {}

  static final String WARMUP = "warm-";
  static final String MEASURED = "load-";
  static final String SLEEPERS = "sleep-";
  private static final Duration DRAIN_LIMIT = Duration.ofMinutes(5);

  private LatencyRun() {}

  static Result run(
      DataSource dataSource,
      double ratePerSecond,
      Duration warmup,
      Duration measured,
      int sleepers,
      EngineSettings settings)
      throws Exception {
    Measurements measurements = new Measurements(dataSource);
    measurements.forgetEverything();
    DurastepClient client = DurastepClient.connect(dataSource);
    AtomicLong late = new AtomicLong();
    AtomicLong failed = new AtomicLong();
    long total = Math.round(ratePerSecond * (warmup.toMillis() + measured.toMillis()) / 1_000.0);
    long warmupCount = Math.round(ratePerSecond * warmup.toMillis() / 1_000.0);
    long periodNanos = Math.round(1_000_000_000.0 / ratePerSecond);
    long sleeperEvery = Math.max(1, (total - warmupCount) / Math.max(1, sleepers));
    AtomicLong next = new AtomicLong();
    try (DurastepEngine engine = settings.engine(dataSource);
        ExecutorService starts = Executors.newVirtualThreadPerTaskExecutor()) {
      engine.start();
      ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
      long beginning = System.nanoTime();
      ScheduledFuture<?> ticks =
          clock.scheduleAtFixedRate(
              () -> {
                long i = next.getAndIncrement();
                if (i >= total) {
                  return;
                }
                long due = beginning + i * periodNanos;
                if (System.nanoTime() - due > TimeUnit.MILLISECONDS.toNanos(50)) {
                  late.incrementAndGet();
                }
                starts.execute(
                    () -> {
                      try {
                        start(client, i, warmupCount, sleeperEvery);
                      } catch (RuntimeException e) {
                        failed.incrementAndGet();
                      }
                    });
              },
              0,
              periodNanos,
              TimeUnit.NANOSECONDS);
      while (next.get() < total) {
        Thread.sleep(Duration.ofMillis(50));
      }
      ticks.cancel(false);
      clock.shutdown();
      if (failed.get() > 0) {
        throw new IllegalStateException(failed.get() + " workflows could not be started");
      }
      waitUntilFinished(measurements, total - warmupCount);
    }
    return new Result(
        ratePerSecond,
        measured,
        late.get(),
        measurements.stepCycles(MEASURED),
        measurements.activityHops(MEASURED),
        measurements.workflows(MEASURED),
        measurements.timerLateness(SLEEPERS),
        settings);
  }

  private static void start(DurastepClient client, long i, long warmupCount, long sleeperEvery) {
    if (i < warmupCount) {
      client.start(ThreeSteps.class, WARMUP + i, "o");
    } else if ((i - warmupCount) % sleeperEvery == 0) {
      client.start(Sleeper.class, SLEEPERS + i, sleepFor(i));
    } else {
      client.start(ThreeSteps.class, MEASURED + i, "o");
    }
  }

  static long sleepFor(long i) {
    return 1_000L + Math.floorMod(i * 7_919L, 9_001L);
  }

  private static void waitUntilFinished(Measurements measurements, long expected) throws Exception {
    long deadline = System.nanoTime() + DRAIN_LIMIT.toNanos();
    while (measurements.completed(MEASURED) + measurements.completed(SLEEPERS) < expected) {
      if (System.nanoTime() > deadline) {
        throw new TimeoutException("the run did not finish within " + DRAIN_LIMIT);
      }
      Thread.sleep(Duration.ofMillis(200));
    }
  }
}
