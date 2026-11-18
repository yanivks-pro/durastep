package io.github.yanivks_pro.durastep.benchmarks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import javax.sql.DataSource;

final class PostgresStats {

  record QueryStat(String query, long calls, double meanMillis, double totalMillis) {}

  record Snapshot(long commits, long rollbacks) {}

  record Sampled(
      Map<String, Long> waits,
      Map<String, Long> lockWaits,
      long samples,
      double postgresCpuPercent) {}

  private static final Duration SAMPLE_EVERY = Duration.ofMillis(100);
  private static final Duration CPU_EVERY = Duration.ofSeconds(1);

  private final DataSource dataSource;
  private final String containerId;

  PostgresStats(DataSource dataSource, String containerId) {
    this.dataSource = dataSource;
    this.containerId = containerId;
  }

  void install() throws SQLException {
    execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
  }

  void reset() throws SQLException {
    execute("SELECT pg_stat_statements_reset()");
    execute("SELECT pg_stat_reset()");
  }

  Snapshot snapshot() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT xact_commit, xact_rollback FROM pg_stat_database"
                    + " WHERE datname = current_database()")) {
      row.next();
      return new Snapshot(row.getLong(1), row.getLong(2));
    }
  }

  List<QueryStat> topStatements(int limit) throws SQLException {
    List<QueryStat> top = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT regexp_replace(query, '\\s+', ' ', 'g'), calls, mean_exec_time,"
                    + " total_exec_time FROM pg_stat_statements"
                    + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname ="
                    + " current_database()) AND query NOT ILIKE '%pg_stat%'"
                    + " ORDER BY total_exec_time DESC LIMIT ?")) {
      statement.setInt(1, limit);
      try (ResultSet row = statement.executeQuery()) {
        while (row.next()) {
          top.add(
              new QueryStat(
                  shorten(row.getString(1)), row.getLong(2), row.getDouble(3), row.getDouble(4)));
        }
      }
    }
    return top;
  }

  Map<String, Long> deadRows() throws SQLException {
    Map<String, Long> dead = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT relname, n_dead_tup FROM pg_stat_user_tables"
                    + " WHERE schemaname = 'durastep' ORDER BY n_dead_tup DESC")) {
      while (row.next()) {
        dead.put(row.getString(1), row.getLong(2));
      }
    }
    return dead;
  }

  Sampler startSampling() {
    Sampler sampler = new Sampler();
    sampler.start();
    return sampler;
  }

  final class Sampler {
    private final Map<String, AtomicLong> waits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lockWaits = new ConcurrentHashMap<>();
    private final AtomicLong samples = new AtomicLong();
    private final DoubleAdder cpu = new DoubleAdder();
    private final AtomicLong cpuSamples = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread waitThread;
    private Thread cpuThread;

    private void start() {
      waitThread = Thread.ofPlatform().daemon().start(this::sampleWaits);
      cpuThread = Thread.ofPlatform().daemon().start(this::sampleCpu);
    }

    Sampled stop() throws InterruptedException {
      running.set(false);
      waitThread.join();
      cpuThread.join();
      long cpuCount = cpuSamples.get();
      return new Sampled(
          sorted(waits),
          sorted(lockWaits),
          samples.get(),
          cpuCount == 0 ? 0 : cpu.sum() / cpuCount);
    }

    private void sampleWaits() {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        while (running.get()) {
          try (ResultSet row =
              statement.executeQuery(
                  "SELECT COALESCE(wait_event_type || ': ' || wait_event, 'running on CPU')"
                      + " FROM pg_stat_activity WHERE state = 'active'"
                      + " AND backend_type = 'client backend' AND pid <> pg_backend_pid()")) {
            while (row.next()) {
              waits.computeIfAbsent(row.getString(1), key -> new AtomicLong()).incrementAndGet();
            }
          }
          try (ResultSet row =
              statement.executeQuery(
                  "SELECT locktype || ' on ' || COALESCE(classid::regclass::text,"
                      + " relation::regclass::text, '-') || ', ' || mode FROM pg_locks"
                      + " WHERE NOT granted")) {
            while (row.next()) {
              lockWaits
                  .computeIfAbsent(row.getString(1), key -> new AtomicLong())
                  .incrementAndGet();
            }
          }
          samples.incrementAndGet();
          Thread.sleep(SAMPLE_EVERY);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("could not sample Postgres's wait events", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void sampleCpu() {
      while (running.get()) {
        try {
          Process stats =
              new ProcessBuilder(
                      "docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", containerId)
                  .redirectErrorStream(true)
                  .start();
          try (BufferedReader out =
              new BufferedReader(
                  new InputStreamReader(stats.getInputStream(), StandardCharsets.UTF_8))) {
            String line = out.readLine();
            if (stats.waitFor() == 0 && line != null && line.endsWith("%")) {
              cpu.add(Double.parseDouble(line.substring(0, line.length() - 1)));
              cpuSamples.incrementAndGet();
            }
          }
          Thread.sleep(CPU_EVERY);
        } catch (IOException | NumberFormatException e) {
          return;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static Map<String, Long> sorted(Map<String, AtomicLong> counts) {
    Map<String, Long> sorted = new LinkedHashMap<>();
    counts.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue().get()));
    return sorted;
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static String shorten(String query) {
    String flat = query.strip();
    return flat.length() <= 90 ? flat : flat.substring(0, 89) + "…";
  }
}
