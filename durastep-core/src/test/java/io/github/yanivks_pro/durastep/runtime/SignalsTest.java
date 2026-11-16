package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.SetStatus;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderActivities;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignalsTest {

  private static final Duration THREE_DAYS = Duration.ofDays(3);

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private static final WorkflowDefinition<String, String> WAITS_FOR_APPROVAL =
      new WorkflowDefinition<>(
          "WaitsForApproval",
          String.class,
          () ->
              (ctx, id) -> {
                ctx.setStatus("waiting for approval");
                String answer = ctx.awaitSignal("approval", String.class);
                ctx.setStatus("approved by " + answer);
                ctx.activities(OrderActivities.class).sendEmail(id);
                return answer;
              });

  private static final WorkflowDefinition<String, String> WAITS_THREE_DAYS =
      new WorkflowDefinition<>(
          "WaitsThreeDays",
          String.class,
          () ->
              (ctx, id) -> {
                Optional<String> answer = ctx.awaitSignal("approval", String.class, THREE_DAYS);
                if (answer.isEmpty()) {
                  return "timed out, then " + ctx.awaitSignal("approval", String.class);
                }
                ctx.activities(OrderActivities.class).sendEmail(id);
                return answer.get();
              });

  private static final WorkflowDefinition<String, String> TWO_SIGNALS =
      new WorkflowDefinition<>(
          "TwoSignals",
          String.class,
          () ->
              (ctx, id) ->
                  ctx.awaitSignal("vote", String.class)
                      + "+"
                      + ctx.awaitSignal("vote", String.class));

  @Test
  void waitingWithNoSignalPublishesTheStatusAndWaitsWithoutNewSteps() {
    List<Command> commands =
        runtime.decide(WAITS_FOR_APPROVAL, "exp-1", History.startedWith("exp-1").entries());

    assertThat(commands).containsExactly(new SetStatus("waiting for approval"));
  }

  @Test
  void aSignalThatArrivedBeforeTheWorkflowWaitedIsKeptAndReceived() {
    History history = History.startedWith("exp-1").signal("approval", "ana");

    List<Command> commands = runtime.decide(WAITS_FOR_APPROVAL, "exp-1", history.entries());

    assertThat(commands)
        .containsExactly(
            new SetStatus("approved by ana"),
            new ScheduleActivity(
                "OrderActivities.sendEmail", new Payload("[\"exp-1\"]"), ActivityOptions.DEFAULT));
  }

  @Test
  void signalsWithTheSameNameAreReceivedOnceEachInTheOrderTheyArrived() {
    History history = History.startedWith("poll").signal("vote", "yes").signal("vote", "no");

    assertThat(runtime.decide(TWO_SIGNALS, "poll", history.entries()))
        .containsExactly(new CompleteWorkflow(json.toPayload("yes+no")));
  }

  @Test
  void waitingWithATimeoutStartsATimerForThatSignal() {
    assertThat(runtime.decide(WAITS_THREE_DAYS, "exp-2", History.startedWith("exp-2").entries()))
        .containsExactly(new StartTimer(THREE_DAYS, "approval"));
  }

  @Test
  void aSignalAlreadyWaitingIsReceivedAtOnceAndTheTimerIsStillRecorded() {
    History history = History.startedWith("exp-2").signal("approval", "ana");

    assertThat(runtime.decide(WAITS_THREE_DAYS, "exp-2", history.entries()))
        .containsExactly(
            new StartTimer(THREE_DAYS, "approval"),
            new ScheduleActivity(
                "OrderActivities.sendEmail", new Payload("[\"exp-2\"]"), ActivityOptions.DEFAULT));
  }

  @Test
  void aSignalThatArrivesBeforeTheTimerFiresWinsAndTheLaterFireIsIgnored() {
    History history =
        History.startedWith("exp-2")
            .timerStartedFor("approval", THREE_DAYS)
            .signal("approval", "ana")
            .timerFired(2)
            .scheduled("OrderActivities.sendEmail", "exp-2")
            .completed(5, null);

    assertThat(runtime.decide(WAITS_THREE_DAYS, "exp-2", history.entries()))
        .containsExactly(new CompleteWorkflow(json.toPayload("ana")));
  }

  @Test
  void whenTheTimerFiresFirstTheWaitIsEmptyAndALaterSignalIsStillReceivedOnce() {
    History history =
        History.startedWith("exp-3")
            .timerStartedFor("approval", THREE_DAYS)
            .timerFired(2)
            .signal("approval", "late ana");

    assertThat(runtime.decide(WAITS_THREE_DAYS, "exp-3", history.entries()))
        .containsExactly(new CompleteWorkflow(json.toPayload("timed out, then late ana")));
  }

  @Test
  void waitingForASignalWhereTheHistoryHasASleepIsANonDeterminismError() {
    History history = History.startedWith("exp-4").timerStarted(THREE_DAYS);

    assertThatThrownBy(() -> runtime.decide(WAITS_THREE_DAYS, "exp-4", history.entries()))
        .isInstanceOf(NonDeterminismException.class)
        .hasMessageContaining("awaitSignal(approval)")
        .hasMessageContaining("sleep");
  }

  @Test
  void replayingTheSameHistoryReceivesTheSameSignalsEveryTime() {
    History history =
        History.startedWith("exp-2").signal("approval", "ana").signal("approval", "bob");

    List<Command> first = runtime.decide(WAITS_THREE_DAYS, "exp-2", history.entries());
    List<Command> second = runtime.decide(WAITS_THREE_DAYS, "exp-2", history.entries());

    assertThat(second).isEqualTo(first);
  }
}
