package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
   * Returns what the workflow is doing now: its state, published status and whether it is blocked.
   *
   * @return the workflow's current description
   */
  WorkflowDescription describe();

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

  /**
   * Waits for the workflow to finish without blocking the caller. The future completes with the
   * result, or exceptionally with {@link WorkflowFailedException} or {@link
   * WorkflowCancelledException}. Cancelling the future stops waiting; the workflow carries on.
   *
   * @return a future of the workflow's result
   */
  CompletableFuture<O> resultAsync();

  /**
   * Sends a signal to this workflow. See {@link DurastepClient#signal(String, String, Object)}.
   *
   * @param name the signal's name
   * @param payload the data to send, converted to JSON
   * @throws IllegalStateException if the workflow has already finished
   */
  void signal(String name, Object payload);

  /**
   * Sends a signal to this workflow at most once per request ID. See {@link
   * DurastepClient#signal(String, String, Object, String)}.
   *
   * @param name the signal's name
   * @param payload the data to send, converted to JSON
   * @param requestId an ID that makes retried sends safe
   * @return {@code true} if the signal was accepted now, {@code false} if this request ID was
   *     already accepted
   * @throws IllegalStateException if the workflow has already finished
   */
  boolean signal(String name, Object payload, String requestId);

  /**
   * Asks this workflow to stop. See {@link DurastepClient#cancel(String)}.
   *
   * @return {@code true} if the request was accepted now, {@code false} if the workflow had already
   *     finished or been asked to cancel
   */
  boolean cancel();

  /**
   * Retries a blocked workflow now instead of after its backoff, for example once a fix is
   * deployed. The action is recorded in the workflow's history with who did it and why.
   *
   * @param reason why, for the audit trail
   * @return {@code true} if the workflow was blocked and will be retried now, {@code false} if it
   *     was not blocked or has finished
   * @throws IllegalArgumentException if the reason is blank
   */
  boolean retryNow(String reason);

  /**
   * Stops this workflow at once, without letting it clean up, and ends it as {@link
   * WorkflowState#CANCELLED}. Use it for workflows that cannot or must not continue, such as a
   * blocked one; {@link #cancel()} lets a workflow clean up instead. The action is recorded in the
   * workflow's history with who did it and why.
   *
   * @param reason why, for the audit trail
   * @return {@code true} if the workflow was terminated, {@code false} if it had already finished
   * @throws IllegalArgumentException if the reason is blank
   */
  boolean terminate(String reason);

  /**
   * Returns the workflow's history: every event recorded for it so far, oldest first.
   *
   * @return the events
   */
  List<HistoryRecord> history();

  /**
   * Returns the error that blocks this workflow, if it is blocked: the exception's type and
   * message, as saved when the workflow last failed to make progress.
   *
   * @return the error, or empty if the workflow is not blocked
   */
  Optional<String> blockedError();
}
