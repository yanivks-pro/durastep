package io.github.yanivks_pro.durastep.api;

import java.util.concurrent.Callable;

/**
 * The trace of one activity attempt, from {@link WorkflowTracing#activityStarted}.
 *
 * <p>The engine calls {@link #wrap} once, runs the returned callable in the activity's own thread,
 * and then calls {@link #end} exactly once.
 */
public interface ActivityTrace {

  /** A trace that does nothing. */
  ActivityTrace NONE =
      new ActivityTrace() {
        @Override
        public <T> Callable<T> wrap(Callable<T> attempt) {
          return attempt;
        }

        @Override
        public void end(ActivityOutcome outcome, String failureType) {}
      };

  /**
   * Returns a callable that runs the attempt with this trace current, so that calls the activity
   * makes, such as HTTP requests, join the workflow's trace.
   *
   * @param attempt the activity's code
   * @param <T> the activity's result type
   * @return the attempt, run inside this trace
   */
  <T> Callable<T> wrap(Callable<T> attempt);

  /**
   * Ends the trace of the attempt.
   *
   * @param outcome how the attempt ended
   * @param failureType the class name of the error if the attempt failed, otherwise {@code null};
   *     never the error's message, which may contain data
   */
  void end(ActivityOutcome outcome, String failureType);
}
