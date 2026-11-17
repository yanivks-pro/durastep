package io.github.yanivks_pro.durastep.examples.expense;

import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Duration;
import java.util.Optional;

public final class ExpenseWorkflow implements Workflow<ExpenseClaim, String> {

  public static final String DECISION = "decision";

  @Override
  public String run(WorkflowContext ctx, ExpenseClaim claim) {
    Approvals approvals = ctx.activities(Approvals.class);
    Optional<Decision> decision;
    try {
      ctx.setStatus("waiting for the manager");
      approvals.askManager(claim);
      decision =
          ctx.awaitSignal(DECISION, Decision.class, Duration.ofSeconds(claim.managerWaitSeconds()));
      if (decision.isEmpty()) {
        ctx.setStatus("escalated to the director");
        approvals.askDirector(claim);
        decision =
            ctx.awaitSignal(
                DECISION, Decision.class, Duration.ofSeconds(claim.directorWaitSeconds()));
      }
    } catch (CancelledException e) {
      ctx.setStatus("withdrawn by " + claim.employee());
      approvals.tellEmployee(claim, "your claim was withdrawn");
      throw e;
    }

    if (decision.isEmpty()) {
      ctx.setStatus("rejected: nobody answered in time");
      approvals.tellEmployee(claim, "rejected, nobody answered in time");
      return "rejected";
    }
    Decision answer = decision.get();
    if (!answer.approved()) {
      ctx.setStatus("rejected by " + answer.by());
      approvals.tellEmployee(claim, "rejected by " + answer.by() + ": " + answer.comment());
      return "rejected";
    }
    ctx.setStatus("approved by " + answer.by() + ", paying");
    approvals.pay(claim);
    approvals.tellEmployee(claim, "approved by " + answer.by() + " and paid");
    ctx.setStatus("paid");
    return "paid";
  }
}
