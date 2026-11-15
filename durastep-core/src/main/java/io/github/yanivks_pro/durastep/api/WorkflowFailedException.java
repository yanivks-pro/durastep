package io.github.yanivks_pro.durastep.api;

/**
 * Thrown by {@link WorkflowHandle#result} when the workflow ended in {@link WorkflowState#FAILED}.
 */
public final class WorkflowFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String workflowId;
  private final String failureType;

  /**
   * Creates the exception.
   *
   * @param workflowId the ID of the failed workflow
   * @param failureType the class name of the error that failed the workflow
   * @param failureMessage the message of that error
   */
  public WorkflowFailedException(String workflowId, String failureType, String failureMessage) {
    super("workflow " + workflowId + " failed: " + failureType + ": " + failureMessage);
    this.workflowId = workflowId;
    this.failureType = failureType;
  }

  /**
   * Returns the ID of the failed workflow.
   *
   * @return the workflow ID
   */
  public String workflowId() {
    return workflowId;
  }

  /**
   * Returns the class name of the error that failed the workflow, for example {@code
   * com.example.CardDeclinedException}.
   *
   * @return the failure's type
   */
  public String failureType() {
    return failureType;
  }
}
