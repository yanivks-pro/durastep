package io.github.yanivks_pro.durastep.examples.load;

import com.sun.net.httpserver.HttpServer;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.expense.Approvals;
import io.github.yanivks_pro.durastep.examples.expense.Decision;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseClaim;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseWorkflow;
import io.github.yanivks_pro.durastep.examples.order.Mail;
import io.github.yanivks_pro.durastep.examples.order.Order;
import io.github.yanivks_pro.durastep.examples.order.OrderWorkflow;
import io.github.yanivks_pro.durastep.examples.order.Payments;
import io.github.yanivks_pro.durastep.examples.order.Warehouse;
import io.github.yanivks_pro.durastep.examples.trip.Bookings;
import io.github.yanivks_pro.durastep.examples.trip.SoldOutException;
import io.github.yanivks_pro.durastep.examples.trip.Trip;
import io.github.yanivks_pro.durastep.examples.trip.TripWorkflow;
import io.github.yanivks_pro.durastep.micrometer.DurastepMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

public final class LoadGenerator {

  private static final Duration START_EVERY = Duration.ofMillis(200);
  private static final Duration REPORT_EVERY = Duration.ofSeconds(10);
  private static final long APPROVAL_WAIT_SECONDS = 2;

  private final PrometheusMeterRegistry registry = newRegistry();
  private final DurastepMetrics metrics = DurastepMetrics.bindTo(registry);
  private final DurastepEngine engine;
  private final HttpServer server;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
  private final List<ScheduledFuture<?>> periodic = new ArrayList<>();
  private final Narrator narrator;
  private final AtomicLong started = new AtomicLong();
  private final AtomicLong lateDecisions = new AtomicLong();

  private LoadGenerator(DataSource dataSource, int port, Narrator narrator) throws IOException {
    this.narrator = narrator;
    this.engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .listener(metrics)
            .workflow(OrderWorkflow.class, OrderWorkflow::new)
            .workflow(TripWorkflow.class, TripWorkflow::new)
            .workflow(ExpenseWorkflow.class, ExpenseWorkflow::new)
            .activities(Payments.class, new FlakyPayments())
            .activities(Warehouse.class, new QuickWarehouse())
            .activities(Mail.class, (customer, orderId, trackingNumber) -> {})
            .activities(Bookings.class, new BusyBookings())
            .activities(Approvals.class, new QuietApprovals())
            .build();
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/metrics",
        exchange -> {
          byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
  }

  public static LoadGenerator start(DataSource dataSource, int port, Narrator narrator)
      throws IOException {
    LoadGenerator generator = new LoadGenerator(dataSource, port, narrator);
    generator.engine.start();
    generator.metrics.watch(generator.engine.client());
    generator.server.start();
    generator.periodic.add(
        generator.scheduler.scheduleAtFixedRate(
            generator::startOneWorkflow, 0, START_EVERY.toMillis(), TimeUnit.MILLISECONDS));
    generator.periodic.add(
        generator.scheduler.scheduleAtFixedRate(
            generator::report,
            REPORT_EVERY.toMillis(),
            REPORT_EVERY.toMillis(),
            TimeUnit.MILLISECONDS));
    narrator.say(
        "load",
        "started; metrics on http://localhost:"
            + generator.port()
            + "/metrics, Grafana on"
            + " http://localhost:3000");
    return generator;
  }

  public static PrometheusMeterRegistry newRegistry() {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    registry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public DistributionStatisticConfig configure(
                  Meter.Id id, DistributionStatisticConfig config) {
                if (id.getType() == Meter.Type.TIMER && id.getName().startsWith("durastep.")) {
                  return DistributionStatisticConfig.builder()
                      .percentilesHistogram(true)
                      .build()
                      .merge(config);
                }
                return config;
              }
            });
    return registry;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public DurastepMetrics metrics() {
    return metrics;
  }

  public PrometheusMeterRegistry registry() {
    return registry;
  }

  public void stop() throws InterruptedException {
    periodic.forEach(task -> task.cancel(false));
    scheduler.shutdownNow();
    server.stop(0);
    metrics.close();
    engine.stop();
    narrator.say(
        "load",
        "stopped after starting "
            + started.get()
            + " workflows; "
            + lateDecisions.get()
            + " decisions came after their claim had closed");
  }

  private void startOneWorkflow() {
    try {
      DurastepClient client = engine.client();
      String id = UUID.randomUUID().toString().substring(0, 8);
      int pick = ThreadLocalRandom.current().nextInt(10);
      if (pick < 4) {
        client.start(
            OrderWorkflow.class, "order-" + id, new Order("order-" + id, "Ada", "keyboard", 4_999));
      } else if (pick < 7) {
        client.start(TripWorkflow.class, "trip-" + id, new Trip("trip-" + id, "Ada", "Lisbon"));
      } else {
        String claimId = "expense-" + id;
        client.start(
            ExpenseWorkflow.class,
            claimId,
            new ExpenseClaim(
                claimId,
                "Ada",
                12_000,
                "conference ticket",
                APPROVAL_WAIT_SECONDS,
                APPROVAL_WAIT_SECONDS));
        scheduleDecision(client, claimId);
      }
      started.incrementAndGet();
    } catch (RuntimeException e) {
      narrator.say("load", "could not start a workflow: " + e.getMessage());
    }
  }

  private void scheduleDecision(DurastepClient client, String claimId) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    if (random.nextInt(5) == 0) {
      return;
    }
    boolean approved = random.nextInt(10) < 7;
    long thinkingMillis = random.nextLong(200, 3_000);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(thinkingMillis);
                client.signal(
                    claimId,
                    ExpenseWorkflow.DECISION,
                    new Decision(approved, "Grace (manager)", approved ? "fine" : "no budget"));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (IllegalStateException claimAlreadyClosedAfterItsTimeout) {
                lateDecisions.incrementAndGet();
              }
            });
  }

  private void report() {
    try {
      EngineStats stats = engine.client().stats();
      narrator.say(
          "load",
          started.get()
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

  private static void pause(int minMillis, int maxMillis) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextInt(minMillis, maxMillis));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static final class FlakyPayments implements Payments {
    @Override
    public String charge(String orderId, long cents) {
      pause(20, 120);
      if (ThreadLocalRandom.current().nextInt(5) == 0) {
        throw new IllegalStateException("payment gateway timed out");
      }
      return "ch_" + orderId;
    }
  }

  static final class QuickWarehouse implements Warehouse {
    @Override
    public void reserve(String orderId, String item) {
      pause(10, 50);
    }

    @Override
    public String ship(String orderId, String customer) {
      pause(30, 150);
      return "TRK-" + orderId;
    }
  }

  static final class BusyBookings implements Bookings {
    @Override
    public String bookFlight(Trip trip) {
      pause(100, 400);
      return "flight for " + trip.id();
    }

    @Override
    public String bookHotel(Trip trip) {
      pause(100, 400);
      return "hotel for " + trip.id();
    }

    @Override
    public String bookCar(Trip trip) {
      pause(50, 200);
      if (ThreadLocalRandom.current().nextInt(6) == 0) {
        throw new SoldOutException("no cars left in " + trip.destination());
      }
      return "car for " + trip.id();
    }

    @Override
    public void cancelFlight(String tripId) {
      pause(20, 80);
    }

    @Override
    public void cancelHotel(String tripId) {
      pause(20, 80);
    }

    @Override
    public void cancelCar(String tripId) {
      pause(20, 80);
    }
  }

  static final class QuietApprovals implements Approvals {
    @Override
    public void askManager(ExpenseClaim claim) {}

    @Override
    public void askDirector(ExpenseClaim claim) {}

    @Override
    public void pay(ExpenseClaim claim) {
      pause(20, 100);
    }

    @Override
    public void tellEmployee(ExpenseClaim claim, String message) {}
  }
}
