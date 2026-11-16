package io.github.yanivks_pro.durastep.api;

/** Starts workflows and finds existing ones. Obtained from {@link DurastepEngine#client()}. */
public interface DurastepClient {

  /**
   * Starts a workflow with a business ID chosen by the caller.
   *
   * <p>Starting is safe to repeat: if a workflow with this ID already exists, nothing new is
   * started and a handle to the existing workflow is returned.
   *
   * @param workflowClass the workflow to start
   * @param workflowId the business ID, for example {@code order-42}; 1 to 255 characters
   * @param input the workflow's input
   * @param <I> the workflow's input type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if a workflow with this ID exists with a different type
   */
  <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass, String workflowId, I input);

  /**
   * Starts a workflow with options, such as a deadline for the whole workflow. Like {@link
   * #start(Class, String, Object)}, it is safe to repeat; the options of an existing workflow are
   * not changed.
   *
   * @param workflowClass the workflow to start
   * @param workflowId the business ID, for example {@code order-42}; 1 to 255 characters
   * @param input the workflow's input
   * @param options the options for this workflow
   * @param <I> the workflow's input type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if a workflow with this ID exists with a different type
   */
  <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass,
      String workflowId,
      I input,
      WorkflowOptions options);

  /**
   * Returns a handle to an existing workflow.
   *
   * @param workflowId the workflow's business ID
   * @param workflowClass the workflow's class, which determines the result type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if no workflow with this ID and type exists
   */
  <O> WorkflowHandle<O> handle(String workflowId, Class<? extends Workflow<?, O>> workflowClass);
}
