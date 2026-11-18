package io.github.yanivks_pro.durastep.dashboard;

final class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  NotFoundException(String message) {
    super(message);
  }
}
