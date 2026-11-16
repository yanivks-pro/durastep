package io.github.yanivks_pro.durastep.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Undoes completed steps when a later step fails, obtained with {@link WorkflowContext#saga()}.
 *
 * <p>After each step that succeeds, register how to undo it. If a later step fails, {@link
 * #compensate()} runs the undo steps in reverse order. Undo steps are normally activity calls, so
 * they are retried and survive crashes like any other step.
 *
 * <pre>{@code
 * Saga saga = ctx.saga();
 * String flight = trips.bookFlight(trip);
 * saga.addCompensation(() -> trips.cancelFlight(flight));
 * String hotel = trips.bookHotel(trip);
 * saga.addCompensation(() -> trips.cancelHotel(hotel));
 * try {
 *   trips.bookCar(trip);
 * } catch (ActivityFailedException e) {
 *   saga.compensate();
 *   throw e;
 * }
 * }</pre>
 */
public final class Saga {

  private final Deque<Runnable> compensations = new ArrayDeque<>();

  Saga() {}

  /**
   * Registers how to undo the step that just succeeded.
   *
   * @param compensation the undo step, usually an activity call
   */
  public void addCompensation(Runnable compensation) {
    compensations.push(Objects.requireNonNull(compensation, "compensation"));
  }

  /**
   * Runs every registered undo step, the most recent first. If one fails, the others still run, and
   * the first failure is thrown once all have been tried. Undo steps that ran are removed, so
   * calling it again does nothing.
   *
   * @throws RuntimeException the first failure of an undo step, if any failed
   */
  public void compensate() {
    RuntimeException firstFailure = null;
    while (!compensations.isEmpty()) {
      Runnable compensation = compensations.pop();
      try {
        compensation.run();
      } catch (RuntimeException e) {
        if (firstFailure == null) {
          firstFailure = e;
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }
}
