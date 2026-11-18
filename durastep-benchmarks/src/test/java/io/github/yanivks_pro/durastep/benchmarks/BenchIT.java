package io.github.yanivks_pro.durastep.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BenchIT {

  @Test
  void aShortRunMeasuresEveryScenarioAndWritesItsResults(@TempDir Path out) throws Exception {
    Bench.main(
        "all",
        "--workflows",
        "200",
        "--warmup",
        "1",
        "--seconds",
        "4",
        "--sleepers",
        "10",
        "--image",
        System.getProperty("postgres.image", "postgres:16-alpine"),
        "--out",
        out.toString());

    JsonNode results = JsonMapper.builder().build().readTree(out.resolve("results.json").toFile());
    assertThat(results.path("throughput").path("workflows").asInt()).isEqualTo(200);
    assertThat(results.path("throughput").path("perSecond").asDouble()).isPositive();
    JsonNode latency = results.path("latency");
    assertThat(latency.path("ratePerSecond").asDouble())
        .isEqualTo(results.path("throughput").path("perSecond").asDouble() / 2);
    assertThat(latency.path("stepCycles").path("count").asLong()).isPositive();
    assertThat(latency.path("stepCycles").path("p50").asDouble()).isPositive();
    assertThat(latency.path("activityHops").path("count").asLong()).isPositive();
    assertThat(latency.path("timerLateness").path("count").asLong()).isPositive();
    assertThat(Files.readString(out.resolve("results.md")))
        .contains("## Throughput (drain test)", "| Step: scheduled → next step recorded |");
  }

  @Test
  void aShortScalingRunUsesSeparateEngineProcessesAndExplainsWhereTimeGoes(@TempDir Path out)
      throws Exception {
    Bench.main(
        "scaling",
        "--workflows",
        "200",
        "--backlog",
        "200",
        "--image",
        System.getProperty("postgres.image", "postgres:16-alpine"),
        "--out",
        out.toString());

    JsonNode results = JsonMapper.builder().build().readTree(out.resolve("results.json").toFile());
    JsonNode scaling = results.path("scaling");
    assertThat(scaling).hasSize(3);
    for (int i = 0; i < 3; i++) {
      JsonNode run = scaling.get(i);
      assertThat(run.path("engines").asInt()).isEqualTo(i + 1);
      assertThat(run.path("perSecond").asDouble()).isPositive();
      assertThat(run.path("commitsPerWorkflow").asDouble()).isPositive();
      assertThat(run.path("engineCpuCores").asDouble()).isPositive();
      assertThat(run.path("topStatements").size()).isPositive();
    }
    assertThat(results.path("backlog")).hasSize(1);
    assertThat(Files.readString(out.resolve("results.md")))
        .contains("## Scaling (drain test, one engine per process)", "## Backlog size");
  }
}
