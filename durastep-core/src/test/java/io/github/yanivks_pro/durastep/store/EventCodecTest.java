package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
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
        new ActivityScheduled("OrderActivities.chargeCard", new Payload("[\"order-42\",4200]")),
        new ActivityCompleted(2, new Payload("\"ch_9f2\"")),
        new ActivityCompleted(4, new Payload("null")),
        new ActivityFailed(2, failure),
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
                "OrderActivities.chargeCard", new Payload("[\"order-42\",4200]")));

    assertThat(payload)
        .isEqualTo(
            "{\"activity\":\"OrderActivities.chargeCard\",\"arguments\":[\"order-42\",4200]}");
  }

  @Test
  void anUnknownEventTypeIsRejected() {
    assertThatThrownBy(() -> codec.decode("TELEPORTED", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TELEPORTED");
  }
}
