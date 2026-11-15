package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Order;
import io.github.yanivks_pro.durastep.runtime.OrderExample.Receipt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayLoopTest {

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);
  private final Map<String, Integer> executions = new LinkedHashMap<>();

  @Test
  void runsToCompletionExecutingEachActivityOnceEvenWhenReplayedAtEveryPoint() {
    History history = History.startedWith(new Order("order-42", 4200));
    List<Command> commands = decideTwice(history);
    int decisions = 1;

    while (commands.getFirst() instanceof ScheduleActivity schedule) {
      history.append(
          History.START.plusSeconds(history.entries().size()),
          new ActivityScheduled(schedule.activity(), schedule.arguments(), schedule.options()));
      assertThat(decideTwice(history)).isEmpty();

      history.completed(history.entries().size(), execute(schedule.activity()));
      commands = decideTwice(history);
      decisions++;
    }

    assertThat(commands.getFirst()).isInstanceOf(CompleteWorkflow.class);
    Receipt receipt =
        (Receipt)
            json.fromPayload(((CompleteWorkflow) commands.getFirst()).result(), Receipt.class);
    assertThat(receipt.chargeId()).isEqualTo("ch_9f2");
    assertThat(executions)
        .containsExactly(
            Map.entry("OrderActivities.chargeCard", 1),
            Map.entry("OrderActivities.reserveStock", 1),
            Map.entry("OrderActivities.sendEmail", 1));
    assertThat(decisions).isEqualTo(4);
    assertThat(history.entries()).hasSize(7);
  }

  @Test
  void neverProducesMoreThanOneCommandPerDecisionForSequentialCode() {
    History history = History.startedWith(new Order("order-42", 4200));
    List<Integer> commandCounts = new ArrayList<>();
    List<Command> commands =
        runtime.decide(OrderExample.definition(), "order-42", history.entries());

    while (!(commands.getFirst() instanceof CompleteWorkflow
        || commands.getFirst() instanceof FailWorkflow)) {
      commandCounts.add(commands.size());
      ScheduleActivity schedule = (ScheduleActivity) commands.getFirst();
      history.scheduled(schedule.activity());
      history.completed(history.entries().size(), execute(schedule.activity()));
      commands = runtime.decide(OrderExample.definition(), "order-42", history.entries());
    }

    assertThat(commandCounts).containsOnly(1);
  }

  private List<Command> decideTwice(History history) {
    List<Command> first = runtime.decide(OrderExample.definition(), "order-42", history.entries());
    List<Command> afterCrash =
        runtime.decide(OrderExample.definition(), "order-42", history.entries());
    assertThat(afterCrash).isEqualTo(first);
    return first;
  }

  private Object execute(String activity) {
    executions.merge(activity, 1, Integer::sum);
    return activity.equals("OrderActivities.chargeCard") ? "ch_9f2" : null;
  }
}
