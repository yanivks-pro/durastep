package io.github.yanivks_pro.durastep.api;

/**
 * Thrown by {@link WorkflowHandle#result} when the workflow ended in {@link
 * WorkflowState#CANCELLED}.
 */
public final class WorkflowCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String workflowId;

  /**
   * Creates the exception.
   *
   * @param workflowId the ID of the cancelled workflow
   */
  public WorkflowCancelledException(String workflowId) {
    super("workflow " + workflowId + " was cancelled");
    this.workflowId = workflowId;
  }

  /**
   * Returns the ID of the cancelled workflow.
   *
   * @return the workflow ID
   */
  public String workflowId() {
    return workflowId;
  }
}
