package io.github.yanivks_pro.durastep.micrometer;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a Durastep engine's events and the database's counts as Micrometer meters.
 *
 * <pre>{@code
 * DurastepMetrics metrics = DurastepMetrics.bindTo(registry);
 * DurastepEngine engine = DurastepEngine.builder().dataSource(dataSource).listener(metrics).build();
 * metrics.watch(engine.client());
 * }</pre>
 *
 * <p>Events become counters and timers: {@code durastep.workflows.started}, {@code
 * durastep.workflows.finished}, {@code durastep.decisions}, {@code durastep.activities}, {@code
 * durastep.timers.lateness}, {@code durastep.leases.expired} and {@code durastep.results.rejected}.
 * {@link #watch(DurastepClient)} adds gauges for the whole database, refreshed every ten seconds:
 * {@code durastep.workflows.running}, {@code durastep.workflows.blocked}, {@code
 * durastep.tasks.ready} by kind and queue, and {@code durastep.tasks.oldest.age}.
 */
public final class DurastepMetrics implements EngineListener, AutoCloseable {

  /** How often {@link #watch(DurastepClient)} reads the counts from the database. */
  public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

  private static final Logger log = LoggerFactory.getLogger(DurastepMetrics.class);

  private final MeterRegistry registry;
  private final CountDownLatch closed = new CountDownLatch(1);
  private volatile EngineStats stats = new EngineStats(0, 0, 0, Map.of(), Duration.ZERO);
  private MultiGauge readyTasks;
  private Thread refresher;

  private DurastepMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Creates metrics that record into the given registry. Pass the result to {@link
   * io.github.yanivks_pro.durastep.api.DurastepEngine.Builder#listener(EngineListener)}.
   *
   * @param registry where to record the meters
   * @return the metrics, which are also an engine listener
   */
  public static DurastepMetrics bindTo(MeterRegistry registry) {
    return new DurastepMetrics(Objects.requireNonNull(registry, "registry"));
  }

  /**
   * Adds gauges with the database's counts, read with {@link DurastepClient#stats()} every ten
   * seconds in the background. Call it once, with any client of the database; {@link #close()}
   * stops the reading.
   *
   * @param client a client of the database to watch
   * @return these metrics
   * @throws IllegalStateException if this is already watching a database
   */
  public DurastepMetrics watch(DurastepClient client) {
    return watch(client, REFRESH_INTERVAL);
  }

  synchronized DurastepMetrics watch(DurastepClient client, Duration interval) {
    Objects.requireNonNull(client, "client");
    if (refresher != null) {
      throw new IllegalStateException("these metrics already watch a database");
    }
    Gauge.builder("durastep.workflows.running", this, metrics -> metrics.stats.running())
        .description("Running workflows, including blocked ones")
        .register(registry);
    Gauge.builder("durastep.workflows.blocked", this, metrics -> metrics.stats.blocked())
        .description("Running workflows blocked by an error")
        .register(registry);
    Gauge.builder(
            "durastep.tasks.oldest.age",
            this,
            metrics -> metrics.stats.oldestReadyWork().toMillis() / 1_000.0)
        .description("How long the oldest waiting decision or activity has waited")
        .baseUnit("seconds")
        .register(registry);
    readyTasks =
        MultiGauge.builder("durastep.tasks.ready")
            .description("Decisions and activities waiting for an engine")
            .register(registry);
    refresher =
        Thread.ofVirtual()
            .name("durastep-metrics")
            .start(() -> refreshUntilClosed(client, interval));
    return this;
  }

  /** Stops reading the database's counts. The meters stay registered. */
  @Override
  public void close() {
    closed.countDown();
    Thread thread;
    synchronized (this) {
      thread = refresher;
    }
    if (thread != null) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void workflowStarted(String type) {
    Counter.builder("durastep.workflows.started")
        .description("Workflows started")
        .tag("type", type)
        .register(registry)
        .increment();
  }

  @Override
  public void workflowFinished(String type, WorkflowState state) {
    Counter.builder("durastep.workflows.finished")
        .description("Workflows that completed, failed or were cancelled")
        .tags("type", type, "state", lowerCase(state))
        .register(registry)
        .increment();
  }

  @Override
  public void decisionMade(String type, Duration took) {
    Timer.builder("durastep.decisions")
        .description("Time from claiming a decision to saving it")
        .tag("type", type)
        .register(registry)
        .record(took);
  }

  @Override
  public void activityFinished(String activity, ActivityOutcome outcome, Duration took) {
    Timer.builder("durastep.activities")
        .description("Activity attempts and how long they ran")
        .tags("activity", activity, "outcome", lowerCase(outcome))
        .register(registry)
        .record(took);
  }

  @Override
  public void timerFired(Duration late) {
    Timer.builder("durastep.timers.lateness")
        .description("How long after their due time timers fired")
        .register(registry)
        .record(late);
  }

  @Override
  public void leaseExpired(WorkKind kind) {
    Counter.builder("durastep.leases.expired")
        .description("Work taken over after its engine stopped renewing its lease")
        .tag("kind", lowerCase(kind))
        .register(registry)
        .increment();
  }

  @Override
  public void staleResultRejected(WorkKind kind) {
    Counter.builder("durastep.results.rejected")
        .description("Results rejected because the work had been taken over")
        .tag("kind", lowerCase(kind))
        .register(registry)
        .increment();
  }

  private void refreshUntilClosed(DurastepClient client, Duration interval) {
    try {
      do {
        refresh(client);
      } while (!closed.await(interval.toMillis(), TimeUnit.MILLISECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void refresh(DurastepClient client) {
    try {
      EngineStats latest = client.stats();
      List<MultiGauge.Row<?>> rows = new ArrayList<>();
      rows.add(
          MultiGauge.Row.of(
              Tags.of("kind", "decision", "queue", "default"), latest.readyDecisions()));
      latest
          .readyActivities()
          .forEach(
              (queue, count) ->
                  rows.add(MultiGauge.Row.of(Tags.of("kind", "activity", "queue", queue), count)));
      stats = latest;
      readyTasks.register(rows, true);
    } catch (RuntimeException e) {
      log.debug("could not read Durastep's counts; the gauges keep their last values", e);
    }
  }

  private static String lowerCase(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
