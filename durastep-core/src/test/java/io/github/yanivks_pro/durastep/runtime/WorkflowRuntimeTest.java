package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Order;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderActivities;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Receipt;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeTest {

  private static final Order ORDER = new Order("order-42", 4200);

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  @Test
  void aNewWorkflowSchedulesItsFirstActivity() {
    History history = History.startedWith(ORDER);

    assertThat(decide(history))
        .containsExactly(
            new ScheduleActivity(
                "OrderActivities.chargeCard",
                new Payload("[\"order-42\",4200]"),
                ActivityOptions.DEFAULT));
  }

  @Test
  void aCompletedStepReturnsItsSavedResultAndTheNextStepIsScheduled() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_9f2");

    assertThat(decide(history))
        .containsExactly(
            new ScheduleActivity(
                "OrderActivities.reserveStock",
                new Payload("[\"order-42\"]"),
                ActivityOptions.DEFAULT));
  }

  @Test
  void aStepWithoutAResultWaitsWithoutNewCommands() {
    History history =
        History.startedWith(ORDER).scheduled("OrderActivities.chargeCard", "order-42", 4200);

    assertThat(decide(history)).isEmpty();
  }

  @Test
  void whenEveryStepHasCompletedTheWorkflowCompletesWithItsResult() {
    History history = completedOrderHistory();

    List<Command> commands = decide(history);

    assertThat(commands).singleElement().isInstanceOf(CompleteWorkflow.class);
    CompleteWorkflow complete = (CompleteWorkflow) commands.getFirst();
    assertThat(json.fromPayload(complete.result(), Receipt.class))
        .isEqualTo(new Receipt("ch_9f2", history.timeOf(7)));
  }

  @Test
  void replayingTheSameHistoryAlwaysGivesTheSameCommands() {
    History history = completedOrderHistory();

    assertThat(decide(history)).isEqualTo(decide(history));
  }

  @Test
  void anActivityFailureThatTheWorkflowDoesNotCatchFailsTheWorkflow() {
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .failed(2, "com.example.CardDeclinedException", "card declined");

    assertThat(decide(history))
        .containsExactly(
            new FailWorkflow(
                new Failure(
                    ActivityFailedException.class.getName(),
                    "OrderActivities.chargeCard failed after 1 attempt:"
                        + " com.example.CardDeclinedException: card declined")));
  }

  @Test
  void workflowCodeCanCatchAnActivityFailureAndCarryOn() {
    List<ActivityFailedException> caught = new ArrayList<>();
    WorkflowDefinition<Order, String> handlesDecline =
        new WorkflowDefinition<>(
            "HandlesDecline",
            Order.class,
            () ->
                (ctx, order) -> {
                  OrderActivities shop = ctx.activities(OrderActivities.class);
                  try {
                    return shop.chargeCard(order.id(), order.cents());
                  } catch (ActivityFailedException e) {
                    caught.add(e);
                    shop.sendEmail(order.id());
                    return "declined";
                  }
                });
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .failed(2, "com.example.CardDeclinedException", "card declined");

    List<Command> commands = runtime.decide(handlesDecline, "order-42", history.entries());

    assertThat(commands)
        .containsExactly(
            new ScheduleActivity(
                "OrderActivities.sendEmail",
                new Payload("[\"order-42\"]"),
                ActivityOptions.DEFAULT));
    assertThat(caught)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.activity()).isEqualTo("OrderActivities.chargeCard");
              assertThat(e.failureType()).isEqualTo("com.example.CardDeclinedException");
              assertThat(e.failureMessage()).isEqualTo("card declined");
              assertThat(e.attempts()).isEqualTo(1);
            });
  }

  @Test
  void theOptionsGivenToAnActivityTravelWithItsCommand() {
    ActivityOptions quick =
        ActivityOptions.builder()
            .attemptTimeout(Duration.ofSeconds(5))
            .retryPolicy(RetryPolicy.builder().maxAttempts(2).build())
            .build();
    WorkflowDefinition<Order, String> withOptions =
        new WorkflowDefinition<>(
            "WithOptions",
            Order.class,
            () ->
                (ctx, order) ->
                    ctx.activities(OrderActivities.class, quick)
                        .chargeCard(order.id(), order.cents()));

    assertThat(runtime.decide(withOptions, "order-42", History.startedWith(ORDER).entries()))
        .containsExactly(
            new ScheduleActivity(
                "OrderActivities.chargeCard", new Payload("[\"order-42\",4200]"), quick));
  }

  @Test
  void callingADifferentActivityThanTheHistoryIsANonDeterminismError() {
    History history = History.startedWith(ORDER).scheduled("OrderActivities.sendEmail", "order-42");

    assertThatThrownBy(() -> decide(history))
        .isInstanceOf(NonDeterminismException.class)
        .hasMessageContaining("OrderActivities.chargeCard")
        .hasMessageContaining("OrderActivities.sendEmail");
  }

  @Test
  void finishingWithFewerStepsThanTheHistoryIsANonDeterminismError() {
    WorkflowDefinition<Order, String> chargeOnly =
        new WorkflowDefinition<>(
            "ChargeOnly",
            Order.class,
            () ->
                (ctx, order) ->
                    ctx.activities(OrderActivities.class).chargeCard(order.id(), order.cents()));
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_9f2")
            .scheduled("OrderActivities.reserveStock", "order-42");

    assertThatThrownBy(() -> runtime.decide(chargeOnly, "order-42", history.entries()))
        .isInstanceOf(NonDeterminismException.class)
        .hasMessageContaining("finished after 1 steps");
  }

  @Test
  void nowIsTheDatabaseTimeOfTheStepTheCodeHasReached() {
    List<Instant> seen = new ArrayList<>();
    WorkflowDefinition<Order, String> recordsTime =
        new WorkflowDefinition<>(
            "RecordsTime",
            Order.class,
            () ->
                (ctx, order) -> {
                  seen.add(ctx.now());
                  String chargeId =
                      ctx.activities(OrderActivities.class).chargeCard(order.id(), order.cents());
                  seen.add(ctx.now());
                  return chargeId;
                });
    History history =
        History.startedWith(ORDER)
            .scheduled("OrderActivities.chargeCard", "order-42", 4200)
            .completed(2, "ch_9f2");

    runtime.decide(recordsTime, "order-42", history.entries());

    assertThat(seen).containsExactly(History.START, history.timeOf(3));
  }

  @Test
  void randomValuesAreTheSameOnEveryReplayAndDifferPerWorkflow() {
    WorkflowDefinition<Order, String> usesRandom =
        new WorkflowDefinition<>(
            "UsesRandom",
            Order.class,
            () -> (ctx, order) -> ctx.randomUUID() + "/" + ctx.random().nextInt());
    History history = History.startedWith(ORDER);

    List<Command> first = runtime.decide(usesRandom, "order-42", history.entries());
    List<Command> replay = runtime.decide(usesRandom, "order-42", history.entries());
    List<Command> otherWorkflow = runtime.decide(usesRandom, "order-43", history.entries());

    assertThat(replay).isEqualTo(first);
    assertThat(otherWorkflow).isNotEqualTo(first);
  }

  @Test
  void catchingThrowableInWorkflowCodeFailsTheWorkflowWithAClearMessage() {
    WorkflowDefinition<Order, String> swallowsEverything =
        new WorkflowDefinition<>(
            "SwallowsEverything",
            Order.class,
            () ->
                (ctx, order) -> {
                  try {
                    return ctx.activities(OrderActivities.class)
                        .chargeCard(order.id(), order.cents());
                  } catch (Throwable ignored) {
                    return "swallowed";
                  }
                });

    assertThat(runtime.decide(swallowsEverything, "order-42", History.startedWith(ORDER).entries()))
        .containsExactly(
            new FailWorkflow(
                new Failure(
                    IllegalStateException.class.getName(), WorkflowRuntime.CAUGHT_SUSPENSION)));
  }

  @Test
  void anExceptionInWorkflowCodeFailsTheWorkflow() {
    WorkflowDefinition<Order, String> broken =
        new WorkflowDefinition<>(
            "Broken",
            Order.class,
            () ->
                (ctx, order) -> {
                  throw new IllegalArgumentException("no such product");
                });

    assertThat(runtime.decide(broken, "order-42", History.startedWith(ORDER).entries()))
        .containsExactly(
            new FailWorkflow(
                new Failure(IllegalArgumentException.class.getName(), "no such product")));
  }

  @Test
  void aHistoryThatDoesNotStartWithWorkflowStartedIsRejected() {
    History history = History.startedWith(ORDER);
    List<HistoryEntry> withoutStart = history.entries().subList(1, 1);

    assertThatThrownBy(() -> runtime.decide(OrderExample.definition(), "order-42", withoutStart))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private List<Command> decide(History history) {
    return runtime.decide(OrderExample.definition(), "order-42", history.entries());
  }

  private static History completedOrderHistory() {
    return History.startedWith(ORDER)
        .scheduled("OrderActivities.chargeCard", "order-42", 4200)
        .completed(2, "ch_9f2")
        .scheduled("OrderActivities.reserveStock", "order-42")
        .completed(4, null)
        .scheduled("OrderActivities.sendEmail", "order-42")
        .completed(6, null);
  }
}
