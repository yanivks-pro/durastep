package io.github.yanivks_pro.durastep.testkit;

import java.util.List;

/**
 * Thrown by {@link WorkflowReplayer} when the code under test no longer replays one or more saved
 * histories. It is an {@link AssertionError}, so test frameworks report it as a failed test.
 */
public final class WorkflowReplayException extends AssertionError {

  private static final long serialVersionUID = 1L;

  /** One line per history that failed, naming the file and what went wrong. */
  private final String[] failures;

  WorkflowReplayException(String summary, List<String> failures) {
    super(summary + "\n- " + String.join("\n- ", failures));
    this.failures = failures.toArray(String[]::new);
  }

  /**
   * Returns one line for every history that failed, naming it and saying what went wrong.
   *
   * @return the failures
   */
  public List<String> failures() {
    return List.of(failures);
  }
}
