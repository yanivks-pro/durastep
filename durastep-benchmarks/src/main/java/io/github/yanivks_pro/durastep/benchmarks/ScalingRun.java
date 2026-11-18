package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.ThreeSteps;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

final class ScalingRun {

  record Connection(String jdbcUrl, String user, String password) {}

  record Result(
      int engines,
      int workflows,
      long measured,
      double perSecond,
      double commitsPerWorkflow,
      double postgresCpuPercent,
      double engineCpuCores,
      Map<String, Long> waits,
      Map<String, Long> lockWaits,
      long waitSamples,
      List<PostgresStats.QueryStat> topStatements,
      Map<String, Long> deadRows) {}

  private static final String WARMUP = "warm-";
  private static final String PREFIX = "scale-";
  private static final Duration LIMIT = Duration.ofMinutes(15);
  private static final int STARTERS = 16;

  private ScalingRun() {}

  static Result run(
      DataSource dataSource,
      PostgresStats stats,
      Connection connection,
      int engines,
      int workflows,
      int pool,
      EngineSettings settings)
      throws Exception {
    Measurements measurements = new Measurements(dataSource);
    measurements.forgetEverything();
    DurastepClient client = DurastepClient.connect(dataSource);
    List<Engine> running = new ArrayList<>();
    try {
      for (int i = 0; i < engines; i++) {
        running.add(Engine.launch(connection, pool, settings));
      }
      int warmup = Math.max(200, workflows / 5);
      queue(client, WARMUP, warmup);
      waitUntilCompleted(measurements, WARMUP, warmup);

      queue(client, PREFIX, workflows);
      Instant queued = measurements.now();
      long doneWhenQueued = measurements.completed(PREFIX);
      stats.reset();
      PostgresStats.Snapshot before = stats.snapshot();
      long cpuBefore = cpuOf(running);
      long windowStart = System.nanoTime();
      PostgresStats.Sampler sampler = stats.startSampling();
      waitUntilCompleted(measurements, PREFIX, workflows);
      double windowSeconds = (System.nanoTime() - windowStart) / 1e9;
      long cpuAfter = cpuOf(running);
      PostgresStats.Sampled sampled = sampler.stop();
      PostgresStats.Snapshot after = stats.snapshot();
      long measured = workflows - doneWhenQueued;
      Duration took = Duration.between(queued, measurements.lastClosed(PREFIX));
      return new Result(
          engines,
          workflows,
          measured,
          measured * 1_000.0 / took.toMillis(),
          (after.commits() - before.commits()) / (double) measured,
          sampled.postgresCpuPercent(),
          (cpuAfter - cpuBefore) / 1e9 / windowSeconds,
          sampled.waits(),
          sampled.lockWaits(),
          sampled.samples(),
          stats.topStatements(8),
          stats.deadRows());
    } finally {
      for (Engine engine : running) {
        engine.stop();
      }
    }
  }

  private static long cpuOf(List<Engine> engines) throws IOException {
    long total = 0;
    for (Engine engine : engines) {
      total += engine.cpuNanos();
    }
    return total;
  }

  private static void queue(DurastepClient client, String prefix, int workflows) {
    AtomicLong failed = new AtomicLong();
    try (ExecutorService starters =
        Executors.newFixedThreadPool(STARTERS, Thread.ofVirtual().factory())) {
      for (int i = 0; i < workflows; i++) {
        String id = prefix + i;
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
  }

  private static void waitUntilCompleted(Measurements measurements, String prefix, int workflows)
      throws SQLException, InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + LIMIT.toNanos();
    while (measurements.completed(prefix) < workflows) {
      if (System.nanoTime() > deadline) {
        throw new TimeoutException("the drain did not finish within " + LIMIT);
      }
      Thread.sleep(Duration.ofMillis(100));
    }
  }

  private record Engine(Process process, BufferedReader out, PrintWriter in) {

    static Engine launch(Connection connection, int pool, EngineSettings settings)
        throws IOException {
      Path java = Path.of(System.getProperty("java.home"), "bin", "java");
      Process process =
          new ProcessBuilder(
                  java.toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  EngineProcess.class.getName(),
                  connection.jdbcUrl(),
                  connection.user(),
                  connection.password(),
                  Integer.toString(pool),
                  Integer.toString(settings.workflowConcurrency()),
                  Integer.toString(settings.activityConcurrency()))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      Engine engine =
          new Engine(
              process,
              new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)),
              new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8));
      engine.await(EngineProcess.READY);
      return engine;
    }

    long cpuNanos() throws IOException {
      in.println(EngineProcess.CPU);
      String line = await(EngineProcess.CPU + " ");
      return Long.parseLong(line.substring(EngineProcess.CPU.length() + 1).strip());
    }

    void stop() throws InterruptedException {
      in.close();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }

    private String await(String prefix) throws IOException {
      String line;
      while ((line = out.readLine()) != null) {
        if (line.startsWith(prefix)) {
          return line;
        }
      }
      throw new IllegalStateException("an engine process exited unexpectedly");
    }
  }
}
