package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * A reference to one workflow, used to wait for its result.
 *
 * @param <O> the type of the workflow's result
 */
public interface WorkflowHandle<O> {

  /**
   * Returns the workflow's ID.
   *
   * @return the business ID the workflow was started with, for example {@code order-42}
   */
  String id();

  /**
   * Waits for the workflow to finish and returns its result.
   *
   * <p>If the timeout passes first, only the wait ends: the workflow keeps running and can be
   * waited for again.
   *
   * @param timeout how long to wait
   * @return the workflow's result
   * @throws WorkflowFailedException if the workflow failed
   * @throws TimeoutException if the workflow did not finish within {@code timeout}
   * @throws InterruptedException if the waiting thread was interrupted
   */
  O result(Duration timeout) throws TimeoutException, InterruptedException;
}
