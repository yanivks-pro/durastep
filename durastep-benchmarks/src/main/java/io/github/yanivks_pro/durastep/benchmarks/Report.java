package io.github.yanivks_pro.durastep.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class Report {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final StringBuilder markdown = new StringBuilder();
  private final Map<String, Object> json = new LinkedHashMap<>();

  Report(Map<String, String> setup) {
    markdown.append("## Setup\n\n| | |\n|---|---|\n");
    setup.forEach(
        (name, value) ->
            markdown.append("| ").append(name).append(" | ").append(value).append(" |\n"));
    json.put("setup", setup);
  }

  void throughput(ThroughputRun.Result result) {
    markdown
        .append("\n## Throughput (drain test)\n\n")
        .append(
            "%,d three-step workflows queued, then one engine started: **%.0f workflows/s** (%.1f s)."
                .formatted(
                    result.workflows(), result.perSecond(), result.took().toMillis() / 1_000.0))
        .append("\n");
    json.put("throughput", result);
  }

  void latency(LatencyRun.Result result) {
    markdown
        .append(
            "\n## Latency under load (open loop)\n\n%.0f workflows/s for %d s after warm-up; %d starts were"
                    .formatted(result.ratePerSecond(), result.measured().toSeconds(), result.late())
                + " more than 50 ms late.\n\n")
        .append("| Measure (ms) | Count | p50 | p90 | p99 | Max |\n|---|---|---|---|---|---|\n")
        .append(result.stepCycles().row("Step: scheduled → next step recorded"))
        .append('\n')
        .append(result.activityHops().row("Activity: scheduled → result arrived"))
        .append('\n')
        .append(result.workflows().row("Workflow: started → finished"))
        .append('\n')
        .append(result.timerLateness().row("Timer: fired after it was due"))
        .append('\n');
    json.put("latency", result);
  }

  void scaling(List<ScalingRun.Result> results) {
    double base = results.getFirst().perSecond();
    markdown
        .append("\n## Scaling (drain test, one engine per process)\n\n")
        .append(
            "| Engines | Workflows/s | Speed-up | Commits per workflow | Postgres CPU | Engine CPU"
                + " (cores) |\n|---|---|---|---|---|---|\n");
    for (ScalingRun.Result result : results) {
      markdown.append(
          "| %d | %.0f | %.2f× | %.1f | %.0f%% | %.2f |\n"
              .formatted(
                  result.engines(),
                  result.perSecond(),
                  result.perSecond() / base,
                  result.commitsPerWorkflow(),
                  result.postgresCpuPercent(),
                  result.engineCpuCores()));
    }
    for (ScalingRun.Result result : results) {
      markdown
          .append("\n### ")
          .append(result.engines())
          .append(result.engines() == 1 ? " engine" : " engines")
          .append("\n\nWhat busy Postgres connections were doing (")
          .append(result.waitSamples())
          .append(" samples, every 100 ms):\n\n| State | Share |\n|---|---|\n");
      long total = result.waits().values().stream().mapToLong(Long::longValue).sum();
      result
          .waits()
          .forEach(
              (state, count) ->
                  markdown.append(
                      "| %s | %.0f%% |\n".formatted(state, 100.0 * count / Math.max(1, total))));
      if (!result.lockWaits().isEmpty()) {
        markdown.append(
            """

            Locks they were waiting for (sum over samples):

            | Lock | Waiting |
            |---|---|
            """);
        result
            .lockWaits()
            .forEach((lock, count) -> markdown.append("| %s | %,d |\n".formatted(lock, count)));
      }
      markdown.append(
          """

          Statements by total time:

          | Statement | Calls | Mean ms | Total ms |
          |---|---|---|---|
          """);
      for (PostgresStats.QueryStat statement : result.topStatements()) {
        markdown.append(
            "| `%s` | %,d | %.2f | %,.0f |\n"
                .formatted(
                    statement.query().replace("|", "\\|"),
                    statement.calls(),
                    statement.meanMillis(),
                    statement.totalMillis()));
      }
    }
    json.put("scaling", results);
  }

  void backlog(List<ScalingRun.Result> results) {
    markdown
        .append("\n## Backlog size (drain test, one engine)\n\n")
        .append(
            """
            | Workflows queued | Workflows/s | Claim query mean ms | Dead rows in tasks after |
            |---|---|---|---|
            """);
    for (ScalingRun.Result result : results) {
      double claim =
          result.topStatements().stream()
              .filter(statement -> statement.query().startsWith("WITH due AS"))
              .mapToDouble(PostgresStats.QueryStat::meanMillis)
              .max()
              .orElse(0);
      markdown.append(
          "| %,d | %.0f | %.2f | %,d |\n"
              .formatted(
                  result.workflows(),
                  result.perSecond(),
                  claim,
                  result.deadRows().getOrDefault("tasks", 0L)));
    }
    json.put("backlog", results);
  }

  String markdown() {
    return markdown.toString();
  }

  void write(Path directory) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("results.md"), markdown);
    Files.writeString(directory.resolve("results.json"), JSON.writeValueAsString(json));
  }
}
