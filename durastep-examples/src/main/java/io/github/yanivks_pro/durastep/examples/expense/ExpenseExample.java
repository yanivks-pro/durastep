package io.github.yanivks_pro.durastep.examples.expense;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.StatusWatcher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

public final class ExpenseExample {

  public enum Scenario {
    APPROVE,
    REJECT,
    ESCALATE,
    NOBODY_ANSWERS,
    WITHDRAW
  }

  public record Result(
      WorkflowState state, String outcome, String finalStatus, long paidCents, List<String> told) {}

  private static final Duration THINKING_TIME = Duration.ofSeconds(1);
  private static final long MANAGER_WAIT_SECONDS = 3;
  private static final long DIRECTOR_WAIT_SECONDS = 3;

  private ExpenseExample() {}

  public static Result run(DataSource dataSource, Scenario scenario, Narrator narrator)
      throws Exception {
    FakeApprovals approvals = new FakeApprovals(narrator);
    try (DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(ExpenseWorkflow.class, ExpenseWorkflow::new)
            .activities(Approvals.class, approvals)
            .build()) {
      engine.start();
      ExpenseClaim claim =
          new ExpenseClaim(
              "expense-" + UUID.randomUUID().toString().substring(0, 8),
              "Ada",
              12_000,
              "conference ticket",
              MANAGER_WAIT_SECONDS,
              DIRECTOR_WAIT_SECONDS);
      narrator.say(
          "employee",
          claim.employee() + " claims " + claim.cents() + " cents for a " + claim.reason());
      WorkflowHandle<String> workflow =
          engine.client().start(ExpenseWorkflow.class, claim.id(), claim);
      StatusWatcher watcher = StatusWatcher.start(workflow, narrator);
      String outcome;
      try {
        playTheirPart(engine, workflow, scenario, narrator);
        outcome = workflow.result(Duration.ofSeconds(30));
      } catch (WorkflowCancelledException e) {
        outcome = "withdrawn";
      } finally {
        watcher.stop();
      }
      narrator.say("employee", "outcome: " + outcome);
      return new Result(
          workflow.describe().state(),
          outcome,
          workflow.describe().status(),
          approvals.paidCents.get(),
          List.copyOf(approvals.told));
    }
  }

  private static void playTheirPart(
      DurastepEngine engine, WorkflowHandle<String> workflow, Scenario scenario, Narrator narrator)
      throws InterruptedException {
    Duration wait = Duration.ofSeconds(20);
    switch (scenario) {
      case APPROVE -> {
        StatusWatcher.awaitStatus(workflow, "waiting for the manager", wait);
        Thread.sleep(THINKING_TIME);
        decide(engine, workflow, narrator, new Decision(true, "Grace (manager)", "fine"));
      }
      case REJECT -> {
        StatusWatcher.awaitStatus(workflow, "waiting for the manager", wait);
        Thread.sleep(THINKING_TIME);
        decide(
            engine,
            workflow,
            narrator,
            new Decision(false, "Grace (manager)", "please use the training budget"));
      }
      case ESCALATE -> {
        StatusWatcher.awaitStatus(workflow, "escalated to the director", wait);
        Thread.sleep(THINKING_TIME);
        decide(engine, workflow, narrator, new Decision(true, "Linus (director)", "approved"));
      }
      case NOBODY_ANSWERS -> {}
      case WITHDRAW -> {
        StatusWatcher.awaitStatus(workflow, "waiting for the manager", wait);
        Thread.sleep(THINKING_TIME);
        narrator.say("employee", "withdraws the claim");
        engine.client().cancel(workflow.id());
      }
    }
  }

  private static void decide(
      DurastepEngine engine,
      WorkflowHandle<String> workflow,
      Narrator narrator,
      Decision decision) {
    narrator.say(
        decision.by().substring(0, decision.by().indexOf(' ')),
        (decision.approved() ? "approves" : "rejects") + ": " + decision.comment());
    engine.client().signal(workflow.id(), ExpenseWorkflow.DECISION, decision);
  }

  static final class FakeApprovals implements Approvals {
    private final Narrator narrator;
    private final AtomicLong paidCents = new AtomicLong();
    private final List<String> told = new CopyOnWriteArrayList<>();

    FakeApprovals(Narrator narrator) {
      this.narrator = narrator;
    }

    @Override
    public void askManager(ExpenseClaim claim) {
      narrator.say("inbox", "asked the manager to approve " + claim.id());
    }

    @Override
    public void askDirector(ExpenseClaim claim) {
      narrator.say("inbox", "no answer from the manager, asked the director");
    }

    @Override
    public void pay(ExpenseClaim claim) {
      paidCents.addAndGet(claim.cents());
      narrator.say("finance", "paid " + claim.cents() + " cents to " + claim.employee());
    }

    @Override
    public void tellEmployee(ExpenseClaim claim, String message) {
      told.add(message);
      narrator.say("inbox", "told " + claim.employee() + ": " + message);
    }
  }
}
