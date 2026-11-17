package io.github.yanivks_pro.durastep.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.examples.load.LoadGenerator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardMatchesMetricsIT {

  private static final Path DASHBOARD =
      Path.of("..", "observability", "grafana", "dashboards", "durastep.json");
  private static final Pattern METRIC = Pattern.compile("durastep_[a-z_]+");

  private static PostgreSQLContainer postgres;

  @BeforeAll
  static void startPostgres() {
    postgres = ExamplePostgres.newContainer();
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void everyMetricTheDashboardQueriesIsServedByTheLoadGenerator() throws Exception {
    Set<String> queried = metricsQueriedBy(DASHBOARD);
    assertThat(queried).hasSizeGreaterThanOrEqualTo(10);
    Narrator quiet =
        new Narrator(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    LoadGenerator load = LoadGenerator.start(ExamplePostgres.dataSourceFor(postgres), 0, quiet);
    try {
      load.metrics().leaseExpired(WorkKind.ACTIVITY);
      load.metrics().staleResultRejected(WorkKind.ACTIVITY);
      HttpClient http = HttpClient.newHttpClient();
      URI endpoint = URI.create("http://localhost:" + load.port() + "/metrics");

      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(
              () -> {
                HttpResponse<String> response =
                    http.send(
                        HttpRequest.newBuilder(endpoint).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(seriesIn(response.body())).containsAll(queried);
              });
    } finally {
      load.stop();
    }
  }

  private static Set<String> metricsQueriedBy(Path dashboard) {
    JsonNode root = JsonMapper.builder().build().readTree(dashboard.toFile());
    Set<String> names = new TreeSet<>();
    for (JsonNode panel : root.required("panels")) {
      for (JsonNode target : panel.path("targets")) {
        Matcher matcher = METRIC.matcher(target.required("expr").asString());
        while (matcher.find()) {
          names.add(matcher.group());
        }
      }
    }
    return names;
  }

  private static Set<String> seriesIn(String scrape) {
    Set<String> names = new TreeSet<>();
    scrape
        .lines()
        .filter(line -> line.startsWith("durastep_"))
        .forEach(line -> names.add(line.split("[{ ]", 2)[0]));
    return names;
  }
}
