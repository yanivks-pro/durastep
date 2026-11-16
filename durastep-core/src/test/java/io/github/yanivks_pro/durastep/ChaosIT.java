package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ChaosIT {

  private static final int WORKFLOWS = Integer.getInteger("chaos.workflows", 1_000);
  private static final int INSTANCES = Integer.getInteger("chaos.instances", 3);
  private static final int KILLS = Integer.getInteger("chaos.kills", 100);
  private static final Path DIRECTORY = Path.of("target", "chaos");
  private static final int SIGKILL_EXIT_CODE = 128 + 9;

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private final List<Process> processes = new ArrayList<>();

  @BeforeAll
  static void startPostgres() throws SQLException, IOException {
    postgres = TestPostgres.newContainer();
    postgres.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    execute("CREATE SCHEMA chaos");
    execute(
        "CREATE TABLE chaos.executions (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " kind text NOT NULL, workflow_id text NOT NULL, idempotency_key text NOT NULL,"
            + " injected_failure boolean NOT NULL, pid bigint NOT NULL)");
    execute(
        "CREATE TABLE chaos.effects (idempotency_key text PRIMARY KEY, kind text NOT NULL,"
            + " workflow_id text NOT NULL)");
    Files.createDirectories(DIRECTORY);
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void stopInstances() throws Exception {
    for (Process process : processes) {
      signal("CONT", process);
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void workflowsSurviveRandomKillsAndFreezesWithNothingLostOrDoneTwice() throws Exception {
    long seed = Long.getLong("chaos.seed", System.nanoTime());
    Random random = new Random(seed);
    long startedAt = System.nanoTime();
    List<Process> instances = new ArrayList<>();
    int[] generations = new int[INSTANCES];
    for (int i = 0; i < INSTANCES; i++) {
      instances.add(launch(i, generations[i]++));
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> exists("durastep.tasks"));
    DurastepClient client = DurastepEngine.builder().dataSource(dataSource).build().client();

    int events = (int) Math.ceil(KILLS / 0.9);
    int perEvent = (int) Math.ceil((double) WORKFLOWS / events);
    int started = 0;
    int kills = 0;
    int freezes = 0;
    while (kills < KILLS) {
      for (int i = 0; i < perEvent && started < WORKFLOWS; i++, started++) {
        client.start(ChaosApp.OrderWorkflow.class, "order-" + started, "order-" + started);
      }
      Thread.sleep(500 + random.nextInt(1_500));
      int victim = random.nextInt(INSTANCES);
      Process process = instances.get(victim);
      if (random.nextInt(10) == 0) {
        signal("STOP", process);
        Thread.sleep(Duration.ofSeconds(3));
        signal("CONT", process);
        freezes++;
      } else {
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(SIGKILL_EXIT_CODE);
        instances.set(victim, launch(victim, generations[victim]++));
        kills++;
      }
    }
    for (; started < WORKFLOWS; started++) {
      client.start(ChaosApp.OrderWorkflow.class, "order-" + started, "order-" + started);
    }
    Duration chaosTook = Duration.ofNanos(System.nanoTime() - startedAt);

    await()
        .atMost(Duration.ofMinutes(10))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> count("durastep.workflows WHERE state <> 'RUNNING'") == WORKFLOWS);
    Duration totalTook = Duration.ofNanos(System.nanoTime() - startedAt);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("seed", seed);
    report.put("instances", INSTANCES);
    report.put("workflows started", WORKFLOWS);
    report.put("completed with the right result", correctlyCompleted());
    report.put("lost", WORKFLOWS - correctlyCompleted());
    report.put("duplicate effects", duplicateEffects());
    report.put("missing effects", missingEffects());
    report.put("kills (kill -9)", kills);
    report.put("freezes (zombies)", freezes);
    report.put("zombie results rejected", zombieRejections());
    report.put("activities", WORKFLOWS * 3);
    report.put("executions", count("chaos.executions"));
    report.put("injected failures (retried)", count("chaos.executions WHERE injected_failure"));
    report.put(
        "repeats absorbed by idempotency keys",
        count("chaos.executions WHERE NOT injected_failure") - WORKFLOWS * 3);
    List<String> violations = InvariantChecker.violations(dataSource);
    report.put("invariant violations", violations.size());
    report.put("chaos phase", chaosTook.truncatedTo(ChronoUnit.SECONDS));
    report.put("total duration", totalTook.truncatedTo(ChronoUnit.SECONDS));
    writeReport(report);

    assertThat(violations).isEmpty();
    assertThat(report.get("lost")).isEqualTo(0);
    assertThat(report.get("duplicate effects")).isEqualTo(0);
    assertThat(report.get("missing effects")).isEqualTo(0);
    assertThat(kills).isGreaterThanOrEqualTo(KILLS);
  }

  private Process launch(int instance, int generation) throws IOException {
    String java = ProcessHandle.current().info().command().orElse("java");
    Path log = DIRECTORY.resolve("instance-" + instance + "-run-" + generation + ".log");
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                ChaosApp.class.getName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .directory(new File("."))
            .start();
    processes.add(process);
    return process;
  }

  private static void writeReport(Map<String, Object> report) throws IOException {
    String text =
        "Durastep chaos report\n"
            + report.entrySet().stream()
                .map(entry -> String.format("  %-38s %s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("\n"))
            + "\n";
    String json =
        report.entrySet().stream()
            .map(
                entry ->
                    "  \""
                        + entry.getKey()
                        + "\": "
                        + (entry.getValue() instanceof Number
                            ? entry.getValue()
                            : "\"" + entry.getValue() + "\""))
            .collect(Collectors.joining(",\n", "{\n", "\n}\n"));
    Files.writeString(DIRECTORY.resolve("report.txt"), text, StandardCharsets.UTF_8);
    Files.writeString(DIRECTORY.resolve("report.json"), json, StandardCharsets.UTF_8);
    System.out.print(text);
    String summary = System.getenv("GITHUB_STEP_SUMMARY");
    if (summary != null) {
      String markdown =
          "## Durastep chaos report\n\n| | |\n|---|---|\n"
              + report.entrySet().stream()
                  .map(entry -> "| " + entry.getKey() + " | " + entry.getValue() + " |")
                  .collect(Collectors.joining("\n"))
              + "\n";
      Files.writeString(
          Path.of(summary),
          markdown,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }

  private static int correctlyCompleted() throws SQLException {
    return count(
        "durastep.workflows WHERE state = 'COMPLETED' AND result::text = '\"ch_' || id || '\"'");
  }

  private static int duplicateEffects() throws SQLException {
    return count(
        "(SELECT workflow_id, kind FROM chaos.effects GROUP BY workflow_id, kind"
            + " HAVING count(*) > 1) duplicates");
  }

  private static int missingEffects() throws SQLException {
    return count(
        "durastep.workflows w WHERE (SELECT count(DISTINCT kind) FROM chaos.effects e"
            + " WHERE e.workflow_id = w.id) <> 3");
  }

  private static long zombieRejections() throws IOException {
    try (Stream<Path> logs = Files.list(DIRECTORY)) {
      long rejected = 0;
      for (Path log : logs.filter(path -> path.toString().endsWith(".log")).toList()) {
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
          rejected += lines.filter(line -> line.contains("discarded")).count();
        }
      }
      return rejected;
    }
  }

  private static void signal(String signal, Process process) throws Exception {
    Process kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).start();
    kill.waitFor(10, TimeUnit.SECONDS);
  }

  private static boolean exists(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
      row.next();
      return row.getBoolean(1);
    }
  }

  private static int count(String from) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT count(*) FROM " + from)) {
      row.next();
      return row.getInt(1);
    }
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
