package io.github.yanivks_pro.durastep.runtime;

final class WorkflowSuspended extends Error {

  private static final long serialVersionUID = 1L;

  WorkflowSuspended() {
    super("Durastep paused this workflow to wait for a step result", null, false, false);
  }
}
