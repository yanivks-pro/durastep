package io.github.yanivks_pro.durastep.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BenchTest {

  @Test
  void optionsAreNamedValuesAndTheScenarioIsTheBareWord() {
    assertThat(Bench.options("latency", "--rate", "120", "--Seconds", "30"))
        .containsEntry("scenario", "latency")
        .containsEntry("rate", "120")
        .containsEntry("seconds", "30");
    assertThatThrownBy(() -> Bench.options("--rate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("missing a value for --rate");
  }

  @Test
  void sleepersSleepBetweenOneAndTenSecondsSpreadEvenly() {
    assertThat(LongStream.range(0, 10_000).map(LatencyRun::sleepFor))
        .allSatisfy(millis -> assertThat(millis).isBetween(1_000L, 10_000L));
    assertThat(LongStream.range(0, 10_000).map(LatencyRun::sleepFor).distinct().count())
        .isGreaterThan(5_000);
  }

  @Test
  void aPercentileRowShowsMillisecondsWithOneDecimal() {
    assertThat(new Percentiles(1_234, 8.64, 10.8, 13.66, 23.8).row("Activity"))
        .isEqualTo("| Activity | 1,234 | 8.6 | 10.8 | 13.7 | 23.8 |");
  }
}
