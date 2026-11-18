package io.github.yanivks_pro.durastep.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HtmlTest {

  @Test
  void escapeReplacesEveryCharacterThatHtmlTreatsAsMarkup() {
    assertThat(Html.escape("<a href=\"x\" title='y'>Tom & Jerry</a>"))
        .isEqualTo("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;Tom &amp; Jerry&lt;/a&gt;");
    assertThat(Html.escape("order-42 ✓")).isEqualTo("order-42 ✓");
  }

  @Test
  void durationsAreShownInTheTwoLargestUnits() {
    assertThat(Html.duration(Duration.ZERO)).isEqualTo("0 s");
    assertThat(Html.duration(Duration.ofMillis(-5))).isEqualTo("0 s");
    assertThat(Html.duration(Duration.ofSeconds(59))).isEqualTo("59 s");
    assertThat(Html.duration(Duration.ofSeconds(125))).isEqualTo("2 min 5 s");
    assertThat(Html.duration(Duration.ofMinutes(64))).isEqualTo("1 h 4 min");
    assertThat(Html.duration(Duration.ofHours(51))).isEqualTo("2 d 3 h");
  }

  @Test
  void timesAreShownInUtcAndMissingTimesAsNothing() {
    assertThat(Html.time(Instant.parse("2026-11-17T23:17:04.512Z")))
        .isEqualTo("2026-11-17 23:17:04 UTC");
    assertThat(Html.time(null)).isEmpty();
  }

  @Test
  void statesAreLabelledInPlainWords() {
    assertThat(Html.label(WorkflowState.RUNNING)).isEqualTo("Running");
    assertThat(Html.label(WorkflowState.CANCELLED)).isEqualTo("Cancelled");
  }
}
