package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowTimeoutException;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.SetStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class WorkflowRuntime {

  public static final int DEADLINE_TIMER_SEQ = 0;

  static final String CAUGHT_SUSPENSION =
      "workflow code caught Durastep's internal WorkflowSuspended error; "
          + "do not catch Throwable or Error in workflow code";

  private final PayloadConverter converter;

  public WorkflowRuntime(PayloadConverter converter) {
    this.converter = converter;
  }

  public List<Command> decide(
      WorkflowDefinition<?, ?> definition, String workflowId, List<HistoryEntry> history) {
    ReplayHistory replay = ReplayHistory.of(history);
    if (replay.deadlinePassed()) {
      return List.of(new FailWorkflow(Failure.of(new WorkflowTimeoutException(workflowId))));
    }
    ReplayContext ctx = new ReplayContext(workflowId, replay, converter);
    ReplayContext.Outcome outcome = ctx.run(() -> run(definition, ctx, replay));

    if (ctx.nonDeterminism() != null) {
      throw ctx.nonDeterminism();
    }
    if (outcome.suspensionCaught()) {
      throw new WorkflowBlockedException(
          workflowId, new IllegalStateException(CAUGHT_SUSPENSION, outcome.thrownAfterCatch()));
    }
    if (!outcome.mainFinished()) {
      return withStatus(ctx, ctx.newCommands());
    }
    Throwable thrown = outcome.thrown();
    if (thrown instanceof ActivityFailedException || thrown instanceof WorkflowFailedException) {
      return withStatus(ctx, List.of(new FailWorkflow(Failure.of(thrown))));
    }
    if (thrown instanceof CancelledException && ctx.cancellationDelivered()) {
      return withStatus(ctx, List.of(new CancelWorkflow()));
    }
    if (thrown != null) {
      throw new WorkflowBlockedException(workflowId, thrown);
    }
    int stepsInHistory = replay.stepsOf(HistoryEvent.MAIN_BRANCH).size();
    if (ctx.mainStepsReached() < stepsInHistory) {
      throw new NonDeterminismException(
          "workflow "
              + workflowId
              + " finished after "
              + ctx.mainStepsReached()
              + " steps, but its history has "
              + stepsInHistory);
    }
    Optional<String> unknownBranch = ctx.branchInHistoryNeverStarted();
    if (unknownBranch.isPresent()) {
      throw new NonDeterminismException(
          "workflow "
              + workflowId
              + " finished without starting branch "
              + unknownBranch.get()
              + ", which its history has");
    }
    if (ctx.cancellationDelivered()) {
      return withStatus(ctx, List.of(new CancelWorkflow()));
    }
    return withStatus(ctx, List.of(new CompleteWorkflow(converter.toPayload(outcome.result()))));
  }

  private static List<Command> withStatus(ReplayContext ctx, List<Command> commands) {
    Optional<String> status = ctx.statusSet();
    if (status.isEmpty()) {
      return commands;
    }
    List<Command> withStatus = new ArrayList<>();
    withStatus.add(new SetStatus(status.get()));
    withStatus.addAll(commands);
    return List.copyOf(withStatus);
  }

  private <I, O> O run(
      WorkflowDefinition<I, O> definition, ReplayContext ctx, ReplayHistory replay) {
    I input =
        definition
            .inputType()
            .cast(converter.fromPayload(replay.started().input(), definition.inputType()));
    Workflow<I, O> workflow = definition.factory().get();
    return workflow.run(ctx, input);
  }
}
