package io.github.yanivks_pro.durastep.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yanivks_pro.durastep.api.HistoryRecord;
import io.github.yanivks_pro.durastep.history.Timeline.Outcome;
import io.github.yanivks_pro.durastep.history.Timeline.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineTest {

  private static final Instant START = Instant.parse("2026-11-17T10:00:00Z");

  private final List<HistoryRecord> events = new ArrayList<>();

  private void event(String type, int secondsAfterStart, String json) {
    events.add(
        new HistoryRecord(events.size() + 1, type, START.plusSeconds(secondsAfterStart), json));
  }

  private static Instant at(int secondsAfterStart) {
    return START.plusSeconds(secondsAfterStart);
  }

  @Test
  void activitiesTimersAndSignalWaitsBecomeStepsWithTheirOutcome() {
    event("WORKFLOW_STARTED", 0, "\"order-1\"");
    event("ACTIVITY_SCHEDULED", 0, "{\"activity\":\"Shop.reserve\",\"arguments\":[]}");
    event("ACTIVITY_COMPLETED", 2, "{\"scheduledSeq\":2,\"result\":\"r1\"}");
    event("ACTIVITY_SCHEDULED", 2, "{\"activity\":\"Shop.charge\",\"branch\":\"1\"}");
    event(
        "ACTIVITY_FAILED",
        9,
        "{\"scheduledSeq\":4,\"attempts\":3,\"failure\":{\"message\":\"card declined\"}}");
    event("TIMER_STARTED", 9, "{\"duration\":\"PT1H\"}");
    event("TIMER_FIRED", 3_609, "{\"startedSeq\":6}");
    event("TIMER_STARTED", 3_609, "{\"duration\":\"P2D\",\"signal\":\"approve\"}");
    event("SIGNAL", 3_700, "{\"name\":\"approve\",\"payload\":true}");
    event("ACTIVITY_SCHEDULED", 3_700, "{\"activity\":\"Shop.ship\"}");
    event("WORKFLOW_COMPLETED", 3_800, "\"done\"");

    assertThat(Timeline.of(events).steps())
        .containsExactly(
            new Step(2, "Shop.reserve", at(0), at(2), Outcome.COMPLETED),
            new Step(4, "Shop.charge (branch 1)", at(2), at(9), Outcome.FAILED),
            new Step(6, "sleeping for PT1H", at(9), at(3_609), Outcome.COMPLETED),
            new Step(
                8, "waiting for signal approve up to P2D", at(3_609), at(3_700), Outcome.COMPLETED),
            new Step(10, "Shop.ship", at(3_700), at(3_800), Outcome.STOPPED));
  }

  @Test
  void aSignalWaitWhoseDeadlinePassesTimesOut() {
    event("WORKFLOW_STARTED", 0, "\"x\"");
    event("TIMER_STARTED", 0, "{\"duration\":\"PT2S\",\"signal\":\"decision\"}");
    event("TIMER_FIRED", 2, "{\"startedSeq\":2}");
    event("SIGNAL", 5, "{\"name\":\"decision\",\"payload\":\"late\"}");

    assertThat(Timeline.of(events).steps())
        .containsExactly(
            new Step(2, "waiting for signal decision up to PT2S", at(0), at(2), Outcome.TIMED_OUT));
  }

  @Test
  void eachEventIsDescribedInPlainWords() {
    event("WORKFLOW_STARTED", 0, "{\"enc\":\"v1\",\"key\":\"k1\",\"data\":\"AAAA\"}");
    event("ACTIVITY_SCHEDULED", 0, "{\"activity\":\"Shop.charge\",\"branch\":\"2\"}");
    event(
        "ACTIVITY_FAILED",
        1,
        "{\"scheduledSeq\":2,\"attempts\":1,\"failure\":{\"message\":\"boom\"}}");
    event("ACTIVITY_COMPLETED", 1, "{\"scheduledSeq\":99}");
    event("TIMER_FIRED", 2, "{\"startedSeq\":0}");
    event("CANCEL_REQUESTED", 3, "{}");
    event("VERSION_MARKER", 3, "{\"changeId\":\"send-sms\",\"version\":1}");
    event("OPERATOR_ACTION", 4, "{\"action\":\"retry-now\",\"by\":\"ada\",\"reason\":\"fixed\"}");
    event("SOMETHING_NEW", 5, "{}");
    event("WORKFLOW_FAILED", 6, "{\"type\":\"x.Refused\",\"message\":\"refused\"}");

    assertThat(Timeline.of(events).entries())
        .extracting(Timeline.Entry::text)
        .containsExactly(
            "started",
            "scheduled Shop.charge (branch 2)",
            "Shop.charge failed after 1 attempt: boom",
            "step #99 completed",
            "deadline passed",
            "asked to cancel",
            "uses version 1 of send-sms",
            "retry-now by ada: fixed",
            "SOMETHING_NEW",
            "failed: refused");
  }

  @Test
  void aRunningWorkflowKeepsItsOpenStepsRunningAndCancellingStopsThem() {
    event("WORKFLOW_STARTED", 0, "\"x\"");
    event("TIMER_STARTED", 1, "{\"duration\":\"PT10M\",\"signal\":\"go\"}");
    event("ACTIVITY_SCHEDULED", 1, "{\"activity\":\"Shop.wait\"}");

    assertThat(Timeline.of(events).steps())
        .extracting(Step::outcome, Step::endedAt)
        .containsExactly(tuple(Outcome.RUNNING, null), tuple(Outcome.RUNNING, null));

    event("WORKFLOW_CANCELLED", 5, "{}");
    assertThat(Timeline.of(events).steps())
        .extracting(Step::outcome)
        .containsExactly(Outcome.STOPPED, Outcome.STOPPED);
    assertThat(Timeline.of(List.of()).steps()).isEmpty();
  }
}
