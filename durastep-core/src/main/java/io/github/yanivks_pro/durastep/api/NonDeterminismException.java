package io.github.yanivks_pro.durastep.api;

/**
 * Thrown when workflow code no longer matches the workflow's saved history, for example because a
 * deployment changed which activity a running workflow calls at a step it has already taken.
 *
 * <p>The engine stops processing the workflow and saves nothing, so its history stays intact.
 */
public final class NonDeterminismException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what differed between the code and the history
   */
  public NonDeterminismException(String message) {
    super(message);
  }
}
