package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Order;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderActivities;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CancellationTest {

  private static final Order ORDER = new Order("order-42", 4200);

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private static final WorkflowDefinition<Order, String> REFUNDS_WHEN_CANCELLED =
      new WorkflowDefinition<>(
          "RefundsWhenCancelled",
          Order.class,
          () ->
              (ctx, order) -> {
                OrderActivities shop = ctx.activities(OrderActivities.class);
                String chargeId = shop.chargeCard(order.id(), order.cents());
                try {
                  shop.reserveStock(order.id());
                  shop.sendEmail(order.id());
                  return chargeId;
                } catch (CancelledException e) {
                  shop.sendEmail("refund " + order.id());
                  throw e;
                }
              });

  @Test
  void aCancellationWhileWaitingForAnActivityLetsTheWorkflowCleanUp() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_1")
            .scheduled("OrderActivities.reserveStock", "order-42")
            .cancelRequested();

    assertThat(decide(history)).containsExactly(refund());
  }

  @Test
  void afterItsCleanupACancelledWorkflowEndsCancelled() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_1")
            .scheduled("OrderActivities.reserveStock", "order-42")
            .cancelRequested()
            .scheduled("OrderActivities.sendEmail", "refund order-42")
            .completed(6, null)
            .completed(4, null);

    assertThat(decide(history)).containsExactly(new CancelWorkflow());
  }

  @Test
  void aResultThatArrivesAfterTheCancellationIsIgnored() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .cancelRequested()
            .completed(2, "ch_1");

    assertThat(decide(history)).containsExactly(new CancelWorkflow());
  }

  @Test
  void aResultFromBeforeTheCancellationIsUsedAndTheNextStepIsNotScheduled() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_1")
            .cancelRequested();

    assertThat(decide(history)).containsExactly(refund());
  }

  @Test
  void aSleepingWorkflowIsCancelledAtOnce() {
    WorkflowDefinition<Order, String> sleeps =
        new WorkflowDefinition<>(
            "Sleeps",
            Order.class,
            () ->
                (ctx, order) -> {
                  ctx.sleep(Duration.ofDays(30));
                  return "woke up";
                });
    History history =
        History.startedWith(ORDER).timerStarted(Duration.ofDays(30)).cancelRequested();

    assertThat(runtime.decide(sleeps, "order-42", history.entries()))
        .containsExactly(new CancelWorkflow());
  }

  @Test
  void aWorkflowWaitingForASignalIsCancelledAndALaterSignalIsIgnored() {
    WorkflowDefinition<Order, String> waits =
        new WorkflowDefinition<>(
            "Waits", Order.class, () -> (ctx, order) -> ctx.awaitSignal("go", String.class));
    History history = History.startedWith(ORDER).cancelRequested().signal("go", "too late");

    assertThat(runtime.decide(waits, "order-42", history.entries()))
        .containsExactly(new CancelWorkflow());
  }

  @Test
  void aWorkflowThatFinishesWithoutWaitingAgainCompletesDespiteTheRequest() {
    WorkflowDefinition<Order, String> instant =
        new WorkflowDefinition<>("Instant", Order.class, () -> (ctx, order) -> "done");
    History history = History.startedWith(ORDER).cancelRequested();

    assertThat(runtime.decide(instant, "order-42", history.entries()))
        .containsExactly(new CompleteWorkflow(json.toPayload("done")));
  }

  @Test
  void aBusinessFailureDuringCleanupFailsTheWorkflow() {
    WorkflowDefinition<Order, String> failsCleanup =
        new WorkflowDefinition<>(
            "FailsCleanup",
            Order.class,
            () ->
                (ctx, order) -> {
                  try {
                    ctx.sleep(Duration.ofDays(1));
                    return "slept";
                  } catch (CancelledException e) {
                    throw new WorkflowFailedException("could not refund");
                  }
                });
    History history = History.startedWith(ORDER).timerStarted(Duration.ofDays(1)).cancelRequested();

    assertThat(runtime.decide(failsCleanup, "order-42", history.entries()))
        .containsExactly(
            new FailWorkflow(
                new Failure(WorkflowFailedException.class.getName(), "could not refund")));
  }

  @Test
  void replayingACancelledHistoryAlwaysGivesTheSameDecision() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_1")
            .scheduled("OrderActivities.reserveStock", "order-42")
            .cancelRequested();

    assertThat(decide(history)).isEqualTo(decide(history));
  }

  private List<Command> decide(History history) {
    return runtime.decide(REFUNDS_WHEN_CANCELLED, "order-42", history.entries());
  }

  private static ScheduleActivity refund() {
    return new ScheduleActivity(
        "OrderActivities.sendEmail", new Payload("[\"refund order-42\"]"), ActivityOptions.DEFAULT);
  }
}
