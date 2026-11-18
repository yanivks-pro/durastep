package io.github.yanivks_pro.durastep.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.history.Timeline;
import io.github.yanivks_pro.durastep.history.Timeline.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkflowPageTest {

  private static final Instant START = Instant.parse("2026-11-17T10:00:00Z");

  @Test
  void barsArePlacedAndSizedByTimeWithAMinimumWidth() {
    List<Timeline.Step> steps =
        List.of(
            new Timeline.Step(2, "first half", START, START.plusSeconds(50), Outcome.COMPLETED),
            new Timeline.Step(
                3, "an instant", START.plusSeconds(50), START.plusSeconds(50), Outcome.FAILED),
            new Timeline.Step(4, "still going", START.plusSeconds(75), null, Outcome.RUNNING),
            new Timeline.Step(5, "waiting", START, START.plusSeconds(10), Outcome.TIMED_OUT));

    String chart = WorkflowPage.chart(steps, START, START.plusSeconds(100));

    assertThat(chart)
        .contains("<rect class=\"bar completed\" x=\"300\" y=\"29\" width=\"340\"")
        .contains("<rect class=\"bar failed\" x=\"640\" y=\"55\" width=\"2\"")
        .contains("<rect class=\"bar running\" x=\"810\" y=\"81\" width=\"170\"")
        .contains("<title>first half: 50 s, completed</title>")
        .contains("<rect class=\"bar timed_out\" x=\"300\" y=\"107\" width=\"68\"")
        .contains("<title>waiting: 10 s, timed out</title>")
        .contains(">1 min 40 s</text>")
        .doesNotContain("The first");
  }

  @Test
  void longNamesAreShortenedInTheChartButNotInTheTooltip() {
    String name = "Payments.chargeTheCustomersCardAndSendAReceipt";
    String chart =
        WorkflowPage.chart(
            List.of(new Timeline.Step(2, name, START, START.plusSeconds(1), Outcome.COMPLETED)),
            START,
            START.plusSeconds(1));

    assertThat(chart).contains(">Payments.chargeTheCustomersCardAndSendA…</text>");
    assertThat(chart).contains("<title>" + name + ": 1 s, completed</title>");
  }

  @Test
  void veryLongHistoriesAreCutWithAPointerToTheCli() {
    List<Timeline.Step> steps =
        IntStream.rangeClosed(1, WorkflowPage.STEPS_SHOWN + 1)
            .mapToObj(i -> new Timeline.Step(i, "step " + i, START, START, Outcome.COMPLETED))
            .toList();
    assertThat(WorkflowPage.chart(steps, START, START)).contains("The first 200 of 201 steps.");

    List<Timeline.Entry> entries =
        IntStream.rangeClosed(1, WorkflowPage.EVENTS_SHOWN + 1)
            .mapToObj(i -> new Timeline.Entry(i, "SIGNAL", START, "received signal x"))
            .toList();
    String events = WorkflowPage.events("order-<1>", entries, START);
    assertThat(events)
        .contains("The first 1000 of 1001 events.")
        .contains("<code>durastep history order-&lt;1&gt;</code>");
  }

  @Test
  void theIdComesFromTheLastPartOfTheAddress() {
    assertThat(WorkflowPage.idFromPath("/workflows/team%2Fa%20b+c")).isEqualTo("team/a b+c");
    assertThatThrownBy(() -> WorkflowPage.idFromPath("/workflows/%zz"))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> WorkflowPage.idFromPath("/workflows/"))
        .isInstanceOf(NotFoundException.class);
  }
}
