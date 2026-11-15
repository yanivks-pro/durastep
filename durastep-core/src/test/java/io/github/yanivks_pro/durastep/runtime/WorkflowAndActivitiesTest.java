package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.activity.ActivityRegistry;
import io.github.yanivks_pro.durastep.activity.ActivityResult;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.activity.ActivityRuntime;
import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Order;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderActivities;
import io.github.yanivks_pro.durastep.runtime.OrderExample.OrderWorkflow;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Receipt;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowAndActivitiesTest {

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime workflows = new WorkflowRuntime(json);
  private final RecordingActivities implementation = new RecordingActivities();
  private final ActivityRuntime activities =
      new ActivityRuntime(
          new ActivityRegistry().register(OrderActivities.class, implementation), json);
  private final WorkflowDefinition<Order, Receipt> definition =
      WorkflowDefinition.of(OrderWorkflow.class, OrderWorkflow::new);

  @Test
  void aWorkflowRunsItsRealActivitiesToCompletion() throws Exception {
    History history = History.startedWith(new Order("order-42", 4200));

    Command last = runToTheEnd(history);

    assertThat(last).isInstanceOf(CompleteWorkflow.class);
    Receipt receipt = (Receipt) json.fromPayload(((CompleteWorkflow) last).result(), Receipt.class);
    assertThat(receipt.chargeId()).isEqualTo("ch_order-42_4200");
    assertThat(implementation.calls)
        .containsExactly(
            "chargeCard order-42/activity-2",
            "reserveStock order-42/activity-4",
            "sendEmail order-42/activity-6");
  }

  @Test
  void aFailingActivityFailsTheWorkflowWithItsError() throws Exception {
    implementation.outOfStock = true;
    History history = History.startedWith(new Order("order-42", 4200));

    Command last = runToTheEnd(history);

    assertThat(last)
        .isEqualTo(
            new FailWorkflow(
                new Failure(
                    ActivityFailedException.class.getName(),
                    "OrderActivities.reserveStock failed after 1 attempt:"
                        + " java.lang.IllegalStateException: out of stock")));
    assertThat(implementation.calls)
        .containsExactly("chargeCard order-42/activity-2", "reserveStock order-42/activity-4");
  }

  private Command runToTheEnd(History history) throws InterruptedException {
    List<Command> commands = workflows.decide(definition, "order-42", history.entries());
    while (commands.getFirst() instanceof ScheduleActivity schedule) {
      int seq = history.entries().size() + 1;
      history.append(
          History.START.plusSeconds(seq),
          new ActivityScheduled(schedule.activity(), schedule.arguments(), schedule.options()));
      ActivityResult result =
          activities.execute(
              schedule.activity(), schedule.arguments(), "order-42", seq, schedule.options());
      history.append(
          History.START.plusSeconds(seq + 1),
          switch (result) {
            case Completed completed -> new ActivityCompleted(seq, completed.result());
            case Failed failed -> new ActivityFailed(seq, failed.failure(), 1);
          });
      commands = workflows.decide(definition, "order-42", history.entries());
    }
    return commands.getFirst();
  }

  static final class RecordingActivities implements OrderActivities {
    final List<String> calls = new ArrayList<>();
    boolean outOfStock;

    @Override
    public String chargeCard(String orderId, long cents) {
      record("chargeCard");
      return "ch_" + orderId + "_" + cents;
    }

    @Override
    public void reserveStock(String orderId) {
      record("reserveStock");
      if (outOfStock) {
        throw new IllegalStateException("out of stock");
      }
    }

    @Override
    public void sendEmail(String orderId) {
      record("sendEmail");
    }

    private void record(String name) {
      calls.add(name + " " + ActivityContext.current().idempotencyKey());
    }
  }
}
