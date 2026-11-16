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
   * Sends a signal to a running workflow, which receives it with {@link
   * WorkflowContext#awaitSignal}. Each call sends a new signal; to make retries of the same call
   * safe, use {@link #signal(String, String, Object, String)} with a request ID.
   *
   * @param workflowId the workflow's business ID
   * @param name the signal's name
   * @param payload the signal's payload
   * @throws IllegalArgumentException if no workflow with this ID exists
   * @throws IllegalStateException if the workflow has already finished
   */
  void signal(String workflowId, String name, Object payload);

  /**
   * Sends a signal once per request ID: repeating the call with the same request ID, for example
   * after a network error, does not deliver the signal a second time.
   *
   * @param workflowId the workflow's business ID
   * @param name the signal's name
   * @param payload the signal's payload
   * @param requestId an ID chosen by the caller, unique for each distinct signal
   * @return {@code true} if the signal was accepted now, {@code false} if this request ID was
   *     already used
   * @throws IllegalArgumentException if no workflow with this ID exists
   * @throws IllegalStateException if the workflow has already finished
   */
  boolean signal(String workflowId, String name, Object payload, String requestId);

  /**
   * Asks a running workflow to stop. The workflow sees a {@link CancelledException} at its next
   * wait point, may clean up, and then ends in {@link WorkflowState#CANCELLED}. Activities that are
   * already running are not interrupted, but their results are ignored.
   *
   * @param workflowId the workflow's business ID
   * @return {@code true} if the request was accepted now, {@code false} if the workflow had already
   *     finished or been asked to cancel
   * @throws IllegalArgumentException if no workflow with this ID exists
   */
  boolean cancel(String workflowId);

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
