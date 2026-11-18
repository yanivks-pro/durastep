package io.github.yanivks_pro.durastep.examples.load;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.expense.Approvals;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseWorkflow;
import io.github.yanivks_pro.durastep.examples.order.Mail;
import io.github.yanivks_pro.durastep.examples.order.OrderWorkflow;
import io.github.yanivks_pro.durastep.examples.order.Payments;
import io.github.yanivks_pro.durastep.examples.order.Warehouse;
import io.github.yanivks_pro.durastep.examples.trip.Bookings;
import io.github.yanivks_pro.durastep.examples.trip.TripWorkflow;
import io.github.yanivks_pro.durastep.micrometer.DurastepMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Demo {

  private Demo() {}

  public static void main(String... args) throws Exception {
    Map<String, String> env = System.getenv();
    Narrator narrator = new Narrator(System.out);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        env.getOrDefault("DURASTEP_DB_URL", "jdbc:postgresql://localhost:15432/durastep"));
    config.setUsername(env.getOrDefault("DURASTEP_DB_USER", "durastep"));
    config.setPassword(env.getOrDefault("DURASTEP_DB_PASSWORD", "durastep"));
    config.setMaximumPoolSize(Integer.parseInt(env.getOrDefault("DEMO_POOL", "10")));
    try (HikariDataSource dataSource = new HikariDataSource(config)) {
      switch (env.getOrDefault("DEMO_ROLE", "engine")) {
        case "engine" ->
            runEngine(
                dataSource,
                Integer.parseInt(env.getOrDefault("DEMO_METRICS_PORT", "8081")),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("DEMO_LEASE_SECONDS", "5"))),
                narrator);
        case "load" ->
            runLoad(
                dataSource,
                Double.parseDouble(env.getOrDefault("DEMO_RATE", "5")),
                Integer.parseInt(env.getOrDefault("DEMO_METRICS_PORT", "8081")),
                narrator);
        default -> throw new IllegalArgumentException("DEMO_ROLE must be engine or load");
      }
    }
  }

  private static void runEngine(
      HikariDataSource dataSource, int port, Duration lease, Narrator narrator) throws Exception {
    PrometheusMeterRegistry registry = LoadGenerator.newRegistry();
    DurastepMetrics metrics = DurastepMetrics.bindTo(registry);
    Ledger ledger = new Ledger(dataSource);
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .leaseDuration(lease)
            .listener(metrics)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .workflow(TripWorkflow.class, TripWorkflow::new)
            .workflow(ExpenseWorkflow.class, ExpenseWorkflow::new)
            .workflow(ReportWorkflow.class, ReportWorkflow::new)
            .activities(Payments.class, RecordedActivities.payments(ledger))
            .activities(Warehouse.class, new LoadGenerator.QuickWarehouse())
            .activities(Mail.class, (customer, orderId, trackingNumber) -> {})
            .activities(Bookings.class, RecordedActivities.bookings(ledger))
            .activities(Approvals.class, RecordedActivities.approvals(ledger))
            .activities(
                ReportWorkflow.Reports.class, new ReportWorkflow.SlowReports(ledger::record))
            .build();
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      engine.close();
                      stopped.countDown();
                    }));
    engine.start();
    metrics.watch(engine.client());
    HttpServer server = MetricsEndpoint.start(registry, port);
    narrator.say("engine", "running; metrics on port " + port);
    try {
      stopped.await();
    } finally {
      server.stop(0);
      metrics.close();
    }
  }

  private static void runLoad(HikariDataSource dataSource, double rate, int port, Narrator narrator)
      throws Exception {
    DurastepClient client = DurastepClient.connect(dataSource);
    PrometheusMeterRegistry registry = LoadGenerator.newRegistry();
    DurastepMetrics metrics = DurastepMetrics.bindTo(registry);
    HttpServer server = MetricsEndpoint.start(registry, port);
    WorkflowMix mix = new WorkflowMix(client, narrator, metrics);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(stopped::countDown));
    try (ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory())) {
      ScheduledFuture<?> starts =
          scheduler.scheduleAtFixedRate(
              mix::startOne, 0, Math.round(1_000_000 / rate), TimeUnit.MICROSECONDS);
      ScheduledFuture<?> reports =
          scheduler.scheduleAtFixedRate(
              () -> report(client, mix, narrator), 10, 10, TimeUnit.SECONDS);
      narrator.say("load", "starting about " + Math.round(rate) + " workflows a second");
      stopped.await();
      starts.cancel(false);
      reports.cancel(false);
    } finally {
      server.stop(0);
      metrics.close();
    }
    narrator.say("load", "stopped after starting " + mix.started() + " workflows");
  }

  private static void report(DurastepClient client, WorkflowMix mix, Narrator narrator) {
    try {
      EngineStats stats = client.stats();
      narrator.say(
          "load",
          mix.started()
              + " started, "
              + stats.running()
              + " running, "
              + stats.blocked()
              + " blocked, oldest waiting work "
              + stats.oldestReadyWork().toMillis()
              + " ms");
    } catch (RuntimeException e) {
      narrator.say("load", "could not read the counts: " + e.getMessage());
    }
  }
}
