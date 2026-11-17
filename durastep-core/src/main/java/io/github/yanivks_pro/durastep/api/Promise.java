package io.github.yanivks_pro.durastep.api;

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
}
