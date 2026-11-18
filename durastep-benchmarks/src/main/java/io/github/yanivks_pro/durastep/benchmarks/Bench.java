package io.github.yanivks_pro.durastep.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class Bench {

  private Bench() {}

  public static void main(String... args) throws Exception {
    Map<String, String> options = options(args);
    String scenario = options.getOrDefault("scenario", "all");
    int workflows = Integer.parseInt(options.getOrDefault("workflows", "10000"));
    int pool = Integer.parseInt(options.getOrDefault("pool", "32"));
    Duration warmup = Duration.ofSeconds(Long.parseLong(options.getOrDefault("warmup", "10")));
    Duration measured = Duration.ofSeconds(Long.parseLong(options.getOrDefault("seconds", "60")));
    int sleepers = Integer.parseInt(options.getOrDefault("sleepers", "1000"));
    EngineSettings settings =
        new EngineSettings(
            Integer.parseInt(
                options.getOrDefault(
                    "workflow-concurrency", "" + EngineSettings.DEFAULTS.workflowConcurrency())),
            Integer.parseInt(
                options.getOrDefault(
                    "activity-concurrency", "" + EngineSettings.DEFAULTS.activityConcurrency())));
    Path out = Path.of(options.getOrDefault("out", "target/benchmarks"));

    try (PostgreSQLContainer postgres =
        new PostgreSQLContainer(options.getOrDefault("image", "postgres:16-alpine"))
            .withCommand(
                "postgres",
                "-c",
                "shared_preload_libraries=pg_stat_statements",
                "-c",
                "pg_stat_statements.track=all")) {
      postgres.start();
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl());
      config.setUsername(postgres.getUsername());
      config.setPassword(postgres.getPassword());
      config.setMaximumPoolSize(pool);
      try (HikariDataSource dataSource = new HikariDataSource(config)) {
        try (DurastepEngine migrate = DurastepEngine.builder().dataSource(dataSource).build()) {
          migrate.start();
        }
        Measurements measurements = new Measurements(dataSource);
        PostgresStats stats = new PostgresStats(dataSource, postgres.getContainerId());
        stats.install();
        Report report = new Report(setup(measurements.postgresVersion(), pool, settings));
        say("warming up the JVM and Postgres");
        ThroughputRun.run(dataSource, Math.max(100, workflows / 5), settings);
        if (scenario.equals("scaling")) {
          ScalingRun.Connection connection =
              new ScalingRun.Connection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          List<ScalingRun.Result> scaling = new ArrayList<>();
          for (int engines = 1; engines <= 3; engines++) {
            say("scaling: draining " + workflows + " workflows with " + engines + " engines");
            scaling.add(
                ScalingRun.run(dataSource, stats, connection, engines, workflows, pool, settings));
            say("scaling: " + Math.round(scaling.getLast().perSecond()) + " workflows/s");
          }
          report.scaling(scaling);
          List<ScalingRun.Result> backlog = new ArrayList<>();
          for (int size : backlogSizes(options.getOrDefault("backlog", "2000,5000,10000,20000"))) {
            say("backlog: draining " + size + " workflows with one engine");
            backlog.add(ScalingRun.run(dataSource, stats, connection, 1, size, pool, settings));
          }
          report.backlog(backlog);
          report.write(out);
          System.out.println();
          System.out.println(report.markdown());
          say("results written to " + out.toAbsolutePath());
          return;
        }
        double rate = 0;
        if (!scenario.equals("latency")) {
          say("throughput: draining " + workflows + " workflows");
          ThroughputRun.Result throughput = ThroughputRun.run(dataSource, workflows, settings);
          report.throughput(throughput);
          rate = throughput.perSecond() / 2;
          say("throughput: " + Math.round(throughput.perSecond()) + " workflows/s");
        }
        if (!scenario.equals("throughput")) {
          rate = Double.parseDouble(options.getOrDefault("rate", rate > 0 ? "" + rate : "100"));
          say("latency: " + Math.round(rate) + " workflows/s for " + measured.toSeconds() + " s");
          report.latency(LatencyRun.run(dataSource, rate, warmup, measured, sleepers, settings));
        }
        report.write(out);
        System.out.println();
        System.out.println(report.markdown());
        say("results written to " + out.toAbsolutePath());
      }
    }
  }

  private static Map<String, String> setup(
      String postgresVersion, int pool, EngineSettings settings) {
    Map<String, String> setup = new LinkedHashMap<>();
    setup.put(
        "Machine",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch")
            + ", "
            + Runtime.getRuntime().availableProcessors()
            + " CPUs visible to the JVM");
    setup.put("Java", System.getProperty("java.vm.name") + " " + Runtime.version());
    setup.put(
        "Postgres", postgresVersion + " in a container, default settings plus pg_stat_statements");
    setup.put("Connection pool", "HikariCP, " + pool + " connections");
    setup.put(
        "Engine",
        "one, "
            + settings.workflowConcurrency()
            + " deciders, "
            + settings.activityConcurrency()
            + " activity workers");
    setup.put("Activities", "do nothing, so the numbers are the engine's own overhead");
    return setup;
  }

  static List<Integer> backlogSizes(String sizes) {
    return Pattern.compile(",")
        .splitAsStream(sizes)
        .map(String::strip)
        .map(Integer::valueOf)
        .toList();
  }

  static Map<String, String> options(String... args) {
    Map<String, String> options = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException("missing a value for " + args[i]);
        }
        options.put(args[i].substring(2).toLowerCase(Locale.ROOT), args[++i]);
      } else {
        options.put("scenario", args[i]);
      }
    }
    return options;
  }

  private static void say(String message) {
    System.out.println("[bench] " + message);
  }
}
