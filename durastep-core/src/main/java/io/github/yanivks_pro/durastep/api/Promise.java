package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.runtime.WorkflowScope;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of a branch started with {@link WorkflowContext#async}, available once the branch has
 * finished.
 *
 * <p>Branches run alongside the code that started them, so their activities run at the same time.
 * They take turns on one logical thread, and their results arrive in the order recorded in the
 * history, so replay always follows the same path.
 *
 * <pre>{@code
 * Promise<String> flight = ctx.async(() -> trips.bookFlight(trip));
 * Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
 * return flight.get() + " and " + hotel.get();
 * }</pre>
 *
 * <p>Durastep provides the implementation. This interface is not meant to be implemented by
 * applications, and it may gain methods in minor releases.
 *
 * @param <T> the type of the branch's result
 */
public interface Promise<T> {

  /**
   * Waits until the branch has finished and returns its result. Like an activity call, this is a
   * wait point: other branches run in the meantime.
   *
   * @return the branch's result; {@code null} for a branch started with a {@link Runnable}
   * @throws RuntimeException the exception the branch ended with, such as {@link
   *     ActivityFailedException} or {@link CancelledException}
   */
  T get();

  /**
   * Returns whether the branch has finished, without waiting.
   *
   * @return {@code true} once the branch has returned or thrown
   */
  boolean isDone();

  /**
   * Returns a promise that is done when all of the given promises are done. Its {@link #get()}
   * returns {@code null}, or rethrows the failure of the branch that failed first in the history.
   *
   * <pre>{@code
   * Promise.allOf(flight, hotel).get();
   * }</pre>
   *
   * @param promises promises returned by {@link WorkflowContext#async}
   * @return a promise of all of them
   * @throws IllegalStateException if called outside workflow code
   */
  static Promise<Void> allOf(Promise<?>... promises) {
    return WorkflowScope.allOf(List.of(promises));
  }

  /**
   * Returns a promise that is done when the first of the given promises is done: first in the
   * history, so replay always picks the same one. Its {@link #get()} returns that branch's result
   * or rethrows its failure.
   *
   * <pre>{@code
   * String cheapest = Promise.anyOf(quoteFromA, quoteFromB).get();
   * }</pre>
   *
   * @param promises promises returned by {@link WorkflowContext#async}
   * @param <T> the type of the result
   * @return a promise of the first result
   * @throws IllegalStateException if called outside workflow code
   */
  @SafeVarargs
  static <T> Promise<T> anyOf(Promise<? extends T>... promises) {
    List<Promise<? extends T>> all = new ArrayList<>();
    for (Promise<? extends T> promise : promises) {
      all.add(promise);
    }
    return WorkflowScope.anyOf(all);
  }
}
