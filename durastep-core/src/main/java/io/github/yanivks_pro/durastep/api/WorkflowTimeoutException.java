package io.github.yanivks_pro.durastep.api;

/**
 * The failure recorded for a workflow that did not finish before the deadline given with {@link
 * WorkflowOptions#timeout}. {@link WorkflowHandle#result} reports it as a {@link
 * WorkflowFailedException} whose {@link WorkflowFailedException#failureType()} is this class's
 * name.
 */
public final class WorkflowTimeoutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param workflowId the workflow that ran out of time
   */
  public WorkflowTimeoutException(String workflowId) {
    super("workflow " + workflowId + " did not finish before its deadline");
  }
}
