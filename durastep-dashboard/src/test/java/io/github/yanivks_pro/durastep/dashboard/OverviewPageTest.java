package io.github.yanivks_pro.durastep.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OverviewPageTest {

  @Test
  void aCardTakesItsAlarmColourOnlyWhenItsCountIsAboveZero() {
    assertThat(OverviewPage.card("/workflows?blocked=yes", 0, "Blocked", "warn"))
        .startsWith("<a class=\"card\" href=\"/workflows?blocked=yes\">");
    assertThat(OverviewPage.card("/workflows?blocked=yes", 3, "Blocked", "warn"))
        .startsWith("<a class=\"card warn\" href=");
    assertThat(OverviewPage.card("/workflows?state=RUNNING", 3, "Running", ""))
        .startsWith("<a class=\"card\" href=");
  }
}
