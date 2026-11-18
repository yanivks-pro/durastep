package io.github.yanivks_pro.durastep.api;

import java.util.Optional;

/**
 * Thrown by {@link WorkflowHandle#result} when the workflow ended in {@link
 * WorkflowState#CANCELLED}.
 */
public final class WorkflowCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The cancelled workflow's ID. */
  private final String workflowId;

  /** Why it was cancelled, or {@code null}. */
  private final String reason;

  /**
   * Creates the exception for a workflow that was cancelled and cleaned up after itself.
   *
   * @param workflowId the ID of the cancelled workflow
   */
  public WorkflowCancelledException(String workflowId) {
    this(workflowId, null);
  }

  /**
   * Creates the exception for a workflow that was cancelled for a given reason, such as being
   * terminated by an operator.
   *
   * @param workflowId the ID of the cancelled workflow
   * @param reason why it was cancelled, or {@code null}
   */
  public WorkflowCancelledException(String workflowId, String reason) {
    super("workflow " + workflowId + (reason == null ? " was cancelled" : " was " + reason));
    this.workflowId = workflowId;
    this.reason = reason;
  }

  /**
   * Returns the ID of the cancelled workflow.
   *
   * @return the workflow ID
   */
  public String workflowId() {
    return workflowId;
  }

  /**
   * Returns why the workflow was cancelled, if that is known: for a terminated workflow, who
   * terminated it and why.
   *
   * @return the reason, such as {@code "terminated by alice: customer fraud"}
   */
  public Optional<String> reason() {
    return Optional.ofNullable(reason);
  }
}
