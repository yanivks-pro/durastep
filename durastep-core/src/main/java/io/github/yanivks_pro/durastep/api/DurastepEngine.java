package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.activity.ActivityRegistry;
import io.github.yanivks_pro.durastep.activity.ActivityRuntime;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.LimitedPayloadConverter;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.WorkflowDefinition;
import io.github.yanivks_pro.durastep.runtime.WorkflowRegistry;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import io.github.yanivks_pro.durastep.store.CrashPoints;
import io.github.yanivks_pro.durastep.store.DatabaseHealth;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.NotificationListener;
import io.github.yanivks_pro.durastep.worker.Retention;
import io.github.yanivks_pro.durastep.worker.SafeEngineListener;
import io.github.yanivks_pro.durastep.worker.Worker;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * The Durastep engine, embedded in an application.
 *
 * <pre>{@code
 * DurastepEngine engine = DurastepEngine.builder()
 *     .dataSource(dataSource)
 *     .workflow(OrderWorkflow.class, OrderWorkflow::new)
 *     .activities(OrderActivities.class, new OrderActivitiesImpl(paymentApi))
 *     .build();
 * engine.start();
 * Receipt receipt = engine.client().start(OrderWorkflow.class, "order-42", order)
 *     .result(Duration.ofSeconds(30));
 * }</pre>
 *
 * <p>Every application instance runs its own engine. All instances coordinate through the shared
 * Postgres database, so any instance can continue a workflow that another one started.
 */
public final class DurastepEngine implements AutoCloseable {

  private static final Duration SAFETY_POLL_INTERVAL = Duration.ofSeconds(1);

  private final DataSource dataSource;
  private final String schema;
  private final boolean applyMigrations;
  private final Worker worker;
  private final DurastepClient client;
  private final NotificationListener listener;
  private final DatabaseHealth health = new DatabaseHealth();
  private final Duration shutdownGrace;
  private final Optional<Retention> retention;

  private DurastepEngine(Builder builder) {
    this.dataSource = Objects.requireNonNull(builder.dataSource, "a dataSource is required");
    this.schema = builder.schema;
    this.applyMigrations = builder.applyMigrations;
    PayloadConverter converter =
        new LimitedPayloadConverter(new JsonPayloadConverter(), builder.maxPayloadSize);
    EngineListener listener = SafeEngineListener.around(builder.listener);
    JdbcWorkflowStore store = new JdbcWorkflowStore(dataSource, schema);
    this.worker =
        new Worker(
            store,
            builder.workflows,
            new WorkflowRuntime(converter),
            new ActivityRuntime(builder.activities, converter),
            new Worker.Settings(
                newOwnerName(),
                builder.leaseDuration,
                SAFETY_POLL_INTERVAL,
                builder.workflowConcurrency,
                builder.activityConcurrency,
                builder.activityQueues,
                builder.historyWarnAt,
                builder.historyLimit),
            CrashPoints.NONE,
            health,
            listener);
    this.client =
        new StoreBackedClient(store, converter, StoreBackedClient.RESULT_POLL_INTERVAL, listener);
    this.listener =
        new NotificationListener(dataSource, store.notificationChannel(), worker::wake, health);
    this.shutdownGrace = builder.shutdownGrace;
    this.retention = builder.retention.map(settings -> new Retention(store, settings));
  }

  /**
   * Returns a builder for configuring an engine.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Applies the database migrations, unless disabled, and starts processing workflows and
   * activities.
   *
   * @throws IllegalStateException if the engine is already running
   */
  public void start() {
    if (applyMigrations) {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .locations("classpath:durastep/db/migration")
          .load()
          .migrate();
    }
    listener.start();
    worker.start();
    retention.ifPresent(Retention::start);
  }

  /**
   * Returns the client for starting workflows and waiting for their results.
   *
   * @return the engine's client
   */
  public DurastepClient client() {
    return client;
  }

  /**
   * Stops claiming new work and gives work in progress the shutdown grace period (30 seconds unless
   * set with {@link Builder#shutdownGrace}) to finish. Work that does not finish in time is
   * interrupted and its leases are released at once, so other engines take it over without waiting
   * for the leases to expire.
   *
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  public void stop() throws InterruptedException {
    if (retention.isPresent()) {
      retention.get().stop();
    }
    worker.stop(shutdownGrace);
    listener.stop();
  }

  /**
   * Same as {@link #stop()}, so the engine can be used in try-with-resources. If the calling thread
   * is interrupted while waiting, it stops waiting and keeps its interrupt status.
   */
  @Override
  public void close() {
    try {
      stop();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String newOwnerName() {
    byte[] random = new byte[4];
    ThreadLocalRandom.current().nextBytes(random);
    return "durastep-" + ProcessHandle.current().pid() + "-" + HexFormat.of().formatHex(random);
  }

  /** Configures and builds a {@link DurastepEngine}. */
  public static final class Builder {

    private DataSource dataSource;
    private String schema = JdbcWorkflowStore.DEFAULT_SCHEMA;
    private boolean applyMigrations = true;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int workflowConcurrency = 10;
    private int activityConcurrency = 100;
    private Set<String> activityQueues = Set.of(JdbcWorkflowStore.DEFAULT_QUEUE);
    private Duration shutdownGrace = Duration.ofSeconds(30);
    private int maxPayloadSize = LimitedPayloadConverter.DEFAULT_LIMIT;
    private EngineListener listener = EngineListener.NONE;
    private int historyWarnAt = Worker.Settings.DEFAULT_HISTORY_WARN_AT;
    private int historyLimit = Worker.Settings.DEFAULT_HISTORY_LIMIT;
    private Optional<Retention.Settings> retention =
        Optional.of(Retention.Settings.keepingFor(Duration.ofDays(30)));
    private final WorkflowRegistry workflows = new WorkflowRegistry();
    private final ActivityRegistry activities = new ActivityRegistry();

    private Builder() {}

    /**
     * Sets the Postgres data source. Required. The application owns the data source and its
     * connection pool.
     *
     * @param dataSource the data source
     * @return this builder
     */
    public Builder dataSource(DataSource dataSource) {
      this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
      return this;
    }

    /**
     * Registers a workflow. Its type name in the database is the class's simple name, so renaming
     * the class breaks workflows that are already running.
     *
     * @param workflowClass the workflow's class
     * @param factory creates a fresh instance for every replay
     * @param <I> the workflow's input type
     * @param <O> the workflow's result type
     * @param <W> the workflow's class
     * @return this builder
     * @throws IllegalArgumentException if a workflow with the same name is already registered
     */
    public <I, O, W extends Workflow<I, O>> Builder workflow(
        Class<W> workflowClass, Supplier<W> factory) {
      workflows.register(WorkflowDefinition.of(workflowClass, factory));
      return this;
    }

    /**
     * Registers the implementation of an activity interface.
     *
     * @param activityInterface the interface that declares the activities
     * @param implementation the object that performs them
     * @param <T> the interface type
     * @return this builder
     * @throws IllegalArgumentException if the interface is invalid or already registered
     */
    public <T> Builder activities(Class<T> activityInterface, T implementation) {
      activities.register(activityInterface, implementation);
      return this;
    }

    /**
     * Sets the Postgres schema for Durastep's tables. Defaults to {@code durastep}.
     *
     * @param schema a lower-case Postgres identifier
     * @return this builder
     */
    public Builder schema(String schema) {
      this.schema = Objects.requireNonNull(schema, "schema");
      return this;
    }

    /**
     * Sets whether {@link DurastepEngine#start()} applies the database migrations. Defaults to
     * {@code true}; turn it off when a DBA applies the scripts instead.
     *
     * @param applyMigrations whether to apply migrations on start
     * @return this builder
     */
    public Builder applyMigrations(boolean applyMigrations) {
      this.applyMigrations = applyMigrations;
      return this;
    }

    /**
     * Sets how long a worker may hold a task before another worker may take it over. If an instance
     * crashes, its unfinished work resumes elsewhere after at most this long. Defaults to 30
     * seconds.
     *
     * @param leaseDuration a positive duration
     * @return this builder
     * @throws IllegalArgumentException if {@code leaseDuration} is zero or negative
     */
    public Builder leaseDuration(Duration leaseDuration) {
      if (leaseDuration.isNegative() || leaseDuration.isZero()) {
        throw new IllegalArgumentException("leaseDuration must be positive, was " + leaseDuration);
      }
      this.leaseDuration = leaseDuration;
      return this;
    }

    /**
     * Sets how many workflow decisions this engine makes at the same time. Defaults to 10. A
     * workflow's decisions never run in parallel with each other, whatever this value.
     *
     * @param workflowConcurrency at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code workflowConcurrency} is below 1
     */
    public Builder workflowConcurrency(int workflowConcurrency) {
      this.workflowConcurrency = requireAtLeastOne(workflowConcurrency, "workflowConcurrency");
      return this;
    }

    /**
     * Sets how many activities this engine runs at the same time, each on its own virtual thread.
     * Defaults to 100. The engine never claims more work than it has free slots, so the rest is
     * left for other engines.
     *
     * @param activityConcurrency at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code activityConcurrency} is below 1
     */
    public Builder activityConcurrency(int activityConcurrency) {
      this.activityConcurrency = requireAtLeastOne(activityConcurrency, "activityConcurrency");
      return this;
    }

    /**
     * Sets which activity queues this engine runs activities from. Defaults to {@code default}. An
     * engine must register the activities of every queue it serves.
     *
     * <pre>{@code
     * builder.activityQueues("default", "gpu");
     * }</pre>
     *
     * @param queues one or more queue names
     * @return this builder
     * @throws IllegalArgumentException if no queue or an invalid name is given
     */
    public Builder activityQueues(String... queues) {
      if (queues.length == 0) {
        throw new IllegalArgumentException("an engine must serve at least one activity queue");
      }
      Set<String> names = new LinkedHashSet<>();
      for (String queue : queues) {
        names.add(ActivityOptions.requireQueueName(queue));
      }
      this.activityQueues = Set.copyOf(names);
      return this;
    }

    /**
     * Sets how long a workflow's history may grow. At {@code warnAt} events the engine logs a
     * warning once; at {@code limit} events the workflow is blocked, so that an operator can
     * terminate or rerun it. Defaults to 10,000 and 50,000. Every decision replays the whole
     * history, so very long histories make a workflow slow.
     *
     * @param warnAt when to warn, at least 1
     * @param limit when to block, more than {@code warnAt}
     * @return this builder
     * @throws IllegalArgumentException if the numbers are out of range
     */
    public Builder historyLimits(int warnAt, int limit) {
      if (warnAt < 1 || limit <= warnAt) {
        throw new IllegalArgumentException(
            "historyLimits needs 1 <= warnAt < limit, was " + warnAt + " and " + limit);
      }
      this.historyWarnAt = warnAt;
      this.historyLimit = limit;
      return this;
    }

    /**
     * Sets the largest size of a single piece of workflow data, such as an input, an activity's
     * arguments or result, or a signal, measured as JSON. Defaults to 256 KB. Larger data throws
     * {@link PayloadTooLargeException}.
     *
     * @param bytes the limit in bytes, at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code bytes} is not positive
     */
    public Builder maxPayloadSize(int bytes) {
      if (bytes < 1) {
        throw new IllegalArgumentException("maxPayloadSize must be positive, was " + bytes);
      }
      this.maxPayloadSize = bytes;
      return this;
    }

    /**
     * Sets a listener for the engine's events, for metrics or tracing. The {@code
     * durastep-micrometer} module provides one that records Micrometer meters.
     *
     * @param listener the listener
     * @return this builder
     */
    public Builder listener(EngineListener listener) {
      this.listener = Objects.requireNonNull(listener, "listener");
      return this;
    }

    /**
     * Sets how long finished workflows are kept. A background job deletes completed, failed and
     * cancelled workflows that closed longer ago than this, with their history, in small batches
     * and on one engine at a time. Running workflows are never deleted. Defaults to 30 days.
     *
     * @param keepFor how long to keep finished workflows, more than zero
     * @return this builder
     * @throws IllegalArgumentException if {@code keepFor} is not positive
     */
    public Builder retention(Duration keepFor) {
      this.retention = Optional.of(Retention.Settings.keepingFor(keepFor));
      return this;
    }

    /**
     * Keeps finished workflows forever, for example when they must be kept for audits. Without
     * retention, the database grows with every workflow.
     *
     * @return this builder
     */
    public Builder noRetention() {
      this.retention = Optional.empty();
      return this;
    }

    /**
     * Sets how long {@link DurastepEngine#stop()} waits for work in progress before releasing it to
     * other engines. Defaults to 30 seconds.
     *
     * @param shutdownGrace zero or more
     * @return this builder
     * @throws IllegalArgumentException if {@code shutdownGrace} is negative
     */
    public Builder shutdownGrace(Duration shutdownGrace) {
      if (shutdownGrace.isNegative()) {
        throw new IllegalArgumentException(
            "shutdownGrace cannot be negative, was " + shutdownGrace);
      }
      this.shutdownGrace = shutdownGrace;
      return this;
    }

    private static int requireAtLeastOne(int value, String name) {
      if (value < 1) {
        throw new IllegalArgumentException(name + " must be at least 1, was " + value);
      }
      return value;
    }

    /**
     * Builds the engine. The engine does nothing until {@link DurastepEngine#start()} is called.
     *
     * @return the engine
     * @throws NullPointerException if no data source was set
     * @throws IllegalArgumentException if the schema name is invalid
     */
    public DurastepEngine build() {
      return new DurastepEngine(this);
    }
  }
}
