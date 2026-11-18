package io.github.yanivks_pro.durastep.api;

/**
 * Thrown when a piece of workflow data is larger than the engine's limit, 256 KB by default (see
 * {@link DurastepEngine.Builder#maxPayloadSize(int)}). Every input, argument, result and signal is
 * stored in the database on every step, so large data slows everything down. Store it elsewhere and
 * pass a reference instead.
 *
 * <p>From {@code client.start} and {@code client.signal}, the caller gets this exception. For an
 * activity's result, the activity fails without retrying, and workflow code receives an {@link
 * ActivityFailedException}. For an activity's arguments or a workflow's result, the workflow is
 * blocked until the code is fixed.
 */
public final class PayloadTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The size of the data, in bytes. */
  private final int size;

  /** The limit it exceeded, in bytes. */
  private final int limit;

  /**
   * Creates the exception.
   *
   * @param size the size of the data in bytes, as JSON
   * @param limit the largest allowed size in bytes
   */
  public PayloadTooLargeException(int size, int limit) {
    super(
        "this data is "
            + kilobytes(size)
            + " as JSON, more than the limit of "
            + kilobytes(limit)
            + ". Store large data elsewhere, for example in object storage, and pass a reference"
            + " such as its key.");
    this.size = size;
    this.limit = limit;
  }

  /**
   * Returns the size of the data.
   *
   * @return the size in bytes, as JSON
   */
  public int size() {
    return size;
  }

  /**
   * Returns the limit that was exceeded.
   *
   * @return the largest allowed size in bytes
   */
  public int limit() {
    return limit;
  }

  private static String kilobytes(int bytes) {
    return (bytes + 1_023) / 1_024 + " KB";
  }
}
