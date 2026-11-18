package io.github.yanivks_pro.durastep.dashboard;

final class BadRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  BadRequestException(String message) {
    super(message);
  }
}
