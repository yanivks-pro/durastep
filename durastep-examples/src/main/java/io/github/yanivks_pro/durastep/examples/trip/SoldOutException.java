package io.github.yanivks_pro.durastep.examples.trip;

public final class SoldOutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SoldOutException(String message) {
    super(message);
  }
}
