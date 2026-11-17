package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.CancelRequested;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.OperatorAction;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.SignalReceived;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.VersionMarker;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCancelled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventCodecTest {

  private final EventCodec codec = new EventCodec();

  static Stream<HistoryEvent> everyEventType() {
    Failure failure = new Failure("com.example.CardDeclinedException", "card declined");
    return Stream.of(
        new WorkflowStarted(new Payload("{\"id\":\"order-42\",\"cents\":4200}")),
        new ActivityScheduled(
            "OrderActivities.chargeCard",
            new Payload("[\"order-42\",4200]"),
            ActivityOptions.DEFAULT),
        new ActivityCompleted(2, new Payload("\"ch_9f2\"")),
        new ActivityCompleted(4, new Payload("null")),
        new ActivityFailed(2, failure, 3),
        new TimerStarted(Duration.ofDays(3)),
        new TimerFired(4),
        new ActivityScheduled(
            "Trips.bookHotel", new Payload("[\"trip-1\"]"), ActivityOptions.DEFAULT, "0.2.1"),
        new TimerStarted(Duration.ofMinutes(5), null, "0.1"),
        new CancelRequested(),
        new OperatorAction("terminate", "alice", "customer fraud"),
        new VersionMarker("send-sms-instead-of-email", 2),
        new WorkflowCancelled(),
        new TimerStarted(Duration.ofDays(3), "approval"),
        new SignalReceived("approval", new Payload("{\"by\":\"ana\"}")),
        new WorkflowCompleted(new Payload("{\"chargeId\":\"ch_9f2\"}")),
        new WorkflowFailed(failure));
  }

  @ParameterizedTest
  @MethodSource("everyEventType")
  void everyEventSurvivesARoundTrip(HistoryEvent event) {
    HistoryEvent decoded = codec.decode(codec.typeOf(event), codec.payloadOf(event));

    assertThat(decoded).isEqualTo(event);
  }

  @Test
  void theStoredShapeOfAScheduledActivityIsStable() {
    String payload =
        codec.payloadOf(
            new ActivityScheduled(
                "OrderActivities.chargeCard",
                new Payload("[\"order-42\",4200]"),
                ActivityOptions.DEFAULT));

    assertThat(payload)
        .isEqualTo(
            "{\"activity\":\"OrderActivities.chargeCard\",\"arguments\":[\"order-42\",4200],"
                + "\"options\":{\"attemptTimeout\":\"PT1M\",\"retry\":{\"maxAttempts\":5,"
                + "\"initialBackoff\":\"PT1S\",\"maxBackoff\":\"PT1M\",\"nonRetryable\":[]}}}");
  }

  @Test
  void aScheduledActivityFromVersionZeroPointOneReadsWithTheDefaultOptions() {
    HistoryEvent decoded =
        codec.decode(
            EventCodec.ACTIVITY_SCHEDULED,
            "{\"activity\": \"OrderActivities.chargeCard\", \"arguments\": [\"order-42\", 4200]}");

    assertThat(decoded)
        .isEqualTo(
            new ActivityScheduled(
                "OrderActivities.chargeCard",
                new Payload("[\"order-42\",4200]"),
                ActivityOptions.DEFAULT));
  }

  @Test
  void aFailedActivityFromVersionZeroPointOneReadsAsOneAttempt() {
    HistoryEvent decoded =
        codec.decode(
            EventCodec.ACTIVITY_FAILED,
            "{\"scheduledSeq\": 2, \"failure\": {\"type\": \"E\", \"message\": \"m\"}}");

    assertThat(decoded).isEqualTo(new ActivityFailed(2, new Failure("E", "m"), 1));
  }

  @Test
  void customOptionsWithATotalTimeoutAndNonRetryableErrorsSurviveARoundTrip() {
    ActivityOptions options =
        ActivityOptions.builder()
            .attemptTimeout(Duration.ofSeconds(10))
            .totalTimeout(Duration.ofMinutes(5))
            .heartbeatTimeout(Duration.ofSeconds(20))
            .queue("gpu")
            .retryPolicy(
                RetryPolicy.builder()
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofMillis(250))
                    .maxBackoff(Duration.ofSeconds(4))
                    .nonRetryable(IllegalArgumentException.class)
                    .build())
            .build();
    ActivityScheduled event = new ActivityScheduled("A.a", new Payload("[]"), options);

    assertThat(codec.decode(codec.typeOf(event), codec.payloadOf(event))).isEqualTo(event);
  }

  @Test
  void anUnknownEventTypeIsRejected() {
    assertThatThrownBy(() -> codec.decode("TELEPORTED", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TELEPORTED");
  }

  @Test
  void stepsOfTheMainCodeAreStoredWithoutABranchSoOlderHistoriesReadTheSame() {
    EventCodec codec = new EventCodec();
    String olderPayload = "{\"activity\":\"Trips.bookFlight\",\"arguments\":[\"trip-1\"]}";

    assertThat(
            codec.payloadOf(
                new ActivityScheduled(
                    "Trips.bookFlight", new Payload("[\"trip-1\"]"), ActivityOptions.DEFAULT)))
        .doesNotContain("branch");
    assertThat(codec.payloadOf(new TimerStarted(Duration.ofMinutes(1)))).doesNotContain("branch");
    assertThat(codec.decode(EventCodec.ACTIVITY_SCHEDULED, olderPayload))
        .isEqualTo(
            new ActivityScheduled(
                "Trips.bookFlight",
                new Payload("[\"trip-1\"]"),
                ActivityOptions.DEFAULT,
                HistoryEvent.MAIN_BRANCH));
  }
}
