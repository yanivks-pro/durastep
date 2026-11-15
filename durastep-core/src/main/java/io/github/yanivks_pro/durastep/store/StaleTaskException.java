package io.github.yanivks_pro.durastep.store;

public final class StaleTaskException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  StaleTaskException(ClaimedTask task) {
    super(
        "task "
            + task.id()
            + " of workflow "
            + task.workflowId()
            + " is no longer held by "
            + task.owner()
            + " with fencing token "
            + task.fencingToken());
  }
}
