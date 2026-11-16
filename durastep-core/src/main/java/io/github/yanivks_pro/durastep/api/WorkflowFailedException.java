package io.github.yanivks_pro.durastep.api;

/**
 * A workflow that ended in {@link WorkflowState#FAILED}.
 *
 * <p>Workflow code throws it to fail on purpose, for a business reason:
 *
 * <pre>{@code
 * if (!stock.available()) {
 *   throw new WorkflowFailedException("out of stock");
 * }
 * }</pre>
 *
 * <p>{@link WorkflowHandle#result} throws it when the workflow has failed, for any reason. Other
 * unexpected exceptions in workflow code do not fail the workflow: they leave it running and
 * blocked until the code is fixed.
 */
public final class WorkflowFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String workflowId;
  private final String failureType;

  /**
   * Creates the exception that workflow code throws to fail on purpose.
   *
   * @param message why the workflow failed
   */
  public WorkflowFailedException(String message) {
    super(message);
    this.workflowId = null;
    this.failureType = WorkflowFailedException.class.getName();
  }

  /**
   * Creates the exception that {@link WorkflowHandle#result} throws for a failed workflow.
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
   * @return the workflow ID, or {@code null} when thrown by workflow code
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
