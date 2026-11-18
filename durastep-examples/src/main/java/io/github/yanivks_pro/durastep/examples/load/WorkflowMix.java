package io.github.yanivks_pro.durastep.examples.load;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.EngineListener;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.expense.Decision;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseClaim;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseWorkflow;
import io.github.yanivks_pro.durastep.examples.order.Order;
import io.github.yanivks_pro.durastep.examples.order.OrderWorkflow;
import io.github.yanivks_pro.durastep.examples.trip.Trip;
import io.github.yanivks_pro.durastep.examples.trip.TripWorkflow;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

final class WorkflowMix {

  private static final long APPROVAL_WAIT_SECONDS = 2;

  private final DurastepClient client;
  private final Narrator narrator;
  private final EngineListener listener;
  private final AtomicLong started = new AtomicLong();
  private final AtomicLong lateDecisions = new AtomicLong();

  WorkflowMix(DurastepClient client, Narrator narrator, EngineListener listener) {
    this.client = client;
    this.narrator = narrator;
    this.listener = listener;
  }

  long started() {
    return started.get();
  }

  long lateDecisions() {
    return lateDecisions.get();
  }

  void startOne() {
    try {
      String id = UUID.randomUUID().toString().substring(0, 8);
      int pick = ThreadLocalRandom.current().nextInt(10);
      if (pick < 4) {
        client.start(
            OrderWorkflow.class, "order-" + id, new Order("order-" + id, "Ada", "keyboard", 4_999));
        listener.workflowStarted("OrderWorkflow");
      } else if (pick < 6) {
        client.start(TripWorkflow.class, "trip-" + id, new Trip("trip-" + id, "Ada", "Lisbon"));
        listener.workflowStarted("TripWorkflow");
      } else if (pick < 7) {
        client.start(ReportWorkflow.class, "report-" + id, "report-" + id);
        listener.workflowStarted("ReportWorkflow");
      } else {
        String claimId = "expense-" + id;
        client.start(
            ExpenseWorkflow.class,
            claimId,
            new ExpenseClaim(
                claimId,
                "Ada",
                12_000,
                "conference ticket",
                APPROVAL_WAIT_SECONDS,
                APPROVAL_WAIT_SECONDS));
        listener.workflowStarted("ExpenseWorkflow");
        scheduleDecision(claimId);
      }
      started.incrementAndGet();
    } catch (RuntimeException e) {
      narrator.say("load", "could not start a workflow: " + e.getMessage());
    }
  }

  private void scheduleDecision(String claimId) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    if (random.nextInt(5) == 0) {
      return;
    }
    boolean approved = random.nextInt(10) < 7;
    long thinkingMillis = random.nextLong(200, 3_000);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(thinkingMillis);
                client.signal(
                    claimId,
                    ExpenseWorkflow.DECISION,
                    new Decision(approved, "Grace (manager)", approved ? "fine" : "no budget"));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (IllegalStateException claimAlreadyClosedAfterItsTimeout) {
                lateDecisions.incrementAndGet();
              }
            });
  }
}
