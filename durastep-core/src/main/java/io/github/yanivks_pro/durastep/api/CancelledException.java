package io.github.yanivks_pro.durastep.api;

/**
 * Thrown in workflow code at its first wait point after {@link DurastepClient#cancel(String)}: an
 * activity call, {@link WorkflowContext#sleep}, or {@link WorkflowContext#awaitSignal}. Results
 * that arrive after the cancellation are ignored.
 *
 * <p>Workflow code may catch it to clean up, for example to refund a payment; activities called
 * afterwards run normally. Whether the code then rethrows it or returns, the workflow ends in
 * {@link WorkflowState#CANCELLED}, unless it throws {@link WorkflowFailedException}.
 *
 * <pre>{@code
 * try {
 *   shop.ship(order.id());
 * } catch (CancelledException e) {
 *   shop.refund(order.id());
 *   throw e;
 * }
 * }</pre>
 */
public final class CancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param workflowId the workflow that was cancelled
   */
  public CancelledException(String workflowId) {
    super("workflow " + workflowId + " was cancelled");
  }
}
