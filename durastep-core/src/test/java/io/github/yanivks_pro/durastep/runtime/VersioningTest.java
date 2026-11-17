package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.RecordVersion;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.VersionMarker;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersioningTest {

  interface Shop {
    String charge(String order);

    void email(String order);

    void sms(String order);
  }

  private static final String CHANGE = "send-sms-instead-of-email";

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private static final WorkflowDefinition<String, String> WITH_VERSION_CHECK =
      new WorkflowDefinition<>(
          "Order",
          String.class,
          () ->
              (ctx, order) -> {
                Shop shop = ctx.activities(Shop.class);
                String charge = shop.charge(order);
                int version = ctx.version(CHANGE, 1);
                if (version >= 1) {
                  shop.sms(order);
                } else {
                  shop.email(order);
                }
                return charge + " v" + version + " v" + ctx.version(CHANGE, 1);
              });

  @Test
  void aWorkflowReachingTheCheckForTheFirstTimeRecordsAndTakesTheNewPath() {
    History charged =
        History.startedWith("order-1").scheduled("Shop.charge", "order-1").completed(2, "ch_1");

    assertThat(decide(charged))
        .containsExactly(new RecordVersion(CHANGE, 1), schedule("Shop.sms", "order-1"));
  }

  @Test
  void aWorkflowThatWentPastTheCheckBeforeItExistedKeepsTheOldPath() {
    History oldCode =
        History.startedWith("order-1")
            .scheduled("Shop.charge", "order-1")
            .completed(2, "ch_1")
            .scheduled("Shop.email", "order-1")
            .completed(4, null);

    assertThat(decide(oldCode)).containsExactly(new CompleteWorkflow(json.toPayload("ch_1 v0 v0")));
  }

  @Test
  void aRecordedVersionIsUsedOnEveryReplayEvenAfterANewerOneIsDeployed() {
    History recorded =
        History.startedWith("order-1")
            .scheduled("Shop.charge", "order-1")
            .completed(2, "ch_1")
            .append(History.START.plusSeconds(3), new VersionMarker(CHANGE, 1))
            .scheduled("Shop.sms", "order-1")
            .completed(5, null);
    WorkflowDefinition<String, String> newerCode =
        new WorkflowDefinition<>(
            "Order",
            String.class,
            () ->
                (ctx, order) -> {
                  Shop shop = ctx.activities(Shop.class);
                  shop.charge(order);
                  int version = ctx.version(CHANGE, 2);
                  shop.sms(order);
                  return "v" + version;
                });

    assertThat(runtime.decide(newerCode, "order-1", recorded.entries()))
        .containsExactly(new CompleteWorkflow(json.toPayload("v1")));
  }

  @Test
  void aVersionCheckDoesNotShiftTheStepNumbersOfAnOldHistory() {
    History waitingForTheEmail =
        History.startedWith("order-1")
            .scheduled("Shop.charge", "order-1")
            .completed(2, "ch_1")
            .scheduled("Shop.email", "order-1");

    assertThat(decide(waitingForTheEmail)).isEmpty();
  }

  @Test
  void theChangeIdAndTheVersionAreChecked() {
    WorkflowDefinition<String, Integer> blank =
        new WorkflowDefinition<>("Blank", String.class, () -> (ctx, order) -> ctx.version(" ", 1));
    WorkflowDefinition<String, Integer> zero =
        new WorkflowDefinition<>(
            "Zero", String.class, () -> (ctx, order) -> ctx.version(CHANGE, 0));

    assertThatThrownBy(() -> runtime.decide(blank, "x", History.startedWith("x").entries()))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> runtime.decide(zero, "x", History.startedWith("x").entries()))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThat(WorkflowContext.DEFAULT_VERSION).isZero();
  }

  private List<Command> decide(History history) {
    return runtime.decide(WITH_VERSION_CHECK, "order-1", history.entries());
  }

  private ScheduleActivity schedule(String activity, Object... arguments) {
    return new ScheduleActivity(activity, json.toArguments(arguments), ActivityOptions.DEFAULT);
  }
}
