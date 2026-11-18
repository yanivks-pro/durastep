package io.github.yanivks_pro.durastep.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

  String markdown() {
    return markdown.toString();
  }

  void write(Path directory) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("results.md"), markdown);
    Files.writeString(directory.resolve("results.json"), JSON.writeValueAsString(json));
  }
}
