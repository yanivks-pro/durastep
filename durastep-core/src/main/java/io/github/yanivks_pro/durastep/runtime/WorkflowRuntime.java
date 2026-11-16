package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowTimeoutException;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    AtomicReference<Object> result = new AtomicReference<>();
    AtomicReference<Throwable> thrown = new AtomicReference<>();

    Thread thread =
        Thread.ofVirtual()
            .name("durastep-workflow-" + workflowId)
            .start(
                () -> {
                  try {
                    result.set(run(definition, ctx, replay));
                  } catch (Throwable error) {
                    thrown.set(error);
                  }
                });
    joinUninterruptibly(thread);

    if (ctx.nonDeterminism() != null) {
      throw ctx.nonDeterminism();
    }
    if (ctx.suspended()) {
      if (!ctx.isSuspension(thrown.get())) {
        throw new WorkflowBlockedException(
            workflowId, new IllegalStateException(CAUGHT_SUSPENSION, thrown.get()));
      }
      return ctx.newCommand() == null ? List.of() : List.of(ctx.newCommand());
    }
    if (thrown.get() instanceof ActivityFailedException
        || thrown.get() instanceof WorkflowFailedException) {
      return List.of(new FailWorkflow(Failure.of(thrown.get())));
    }
    if (thrown.get() != null) {
      throw new WorkflowBlockedException(workflowId, thrown.get());
    }
    if (ctx.stepsReached() < replay.steps().size()) {
      throw new NonDeterminismException(
          "workflow "
              + workflowId
              + " finished after "
              + ctx.stepsReached()
              + " steps, but its history has "
              + replay.steps().size());
    }
    return List.of(new CompleteWorkflow(converter.toPayload(result.get())));
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

  private static void joinUninterruptibly(Thread thread) {
    boolean interrupted = false;
    while (true) {
      try {
        thread.join();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
