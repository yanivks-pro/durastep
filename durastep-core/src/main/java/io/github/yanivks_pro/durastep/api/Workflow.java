package io.github.yanivks_pro.durastep.api;

/**
 * A durable business process, written as plain Java.
 *
 * <p>The engine may run {@link #run} many times for the same workflow: after every step, and again
 * after any crash or restart. Each run replays the steps that already happened, using their saved
 * results, and then continues with the next step. For this to work, workflow code must be
 * <em>deterministic</em>: given the same input and the same step results, it must make the same
 * calls in the same order.
 *
 * <ul>
 *   <li>Do real work (API calls, database writes, emails) only in activities, obtained with {@link
 *       WorkflowContext#activities}.
 *   <li>Use {@link WorkflowContext#now()}, {@link WorkflowContext#random()} and {@link
 *       WorkflowContext#randomUUID()} instead of the system clock and random number generators.
 *   <li>Do not catch {@link Throwable} or {@link Error}: the engine uses an internal error to pause
 *       a workflow while it waits.
 * </ul>
 *
 * @param <I> the type of the workflow's input
 * @param <O> the type of the workflow's result
 */
@FunctionalInterface
public interface Workflow<I, O> {

  /**
   * Runs the workflow from the beginning.
   *
   * @param ctx the workflow's context, the only safe way to reach activities, time and randomness
   * @param input the input the workflow was started with
   * @return the workflow's result
   */
  O run(WorkflowContext ctx, I input);
}
