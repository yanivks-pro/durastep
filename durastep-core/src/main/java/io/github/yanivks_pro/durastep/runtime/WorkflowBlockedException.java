package io.github.yanivks_pro.durastep.runtime;

public final class WorkflowBlockedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  WorkflowBlockedException(String workflowId, Throwable cause) {
    super("workflow " + workflowId + " is blocked by an unexpected error: " + cause, cause);
  }
}
