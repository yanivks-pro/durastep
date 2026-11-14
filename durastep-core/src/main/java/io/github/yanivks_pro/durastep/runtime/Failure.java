package io.github.yanivks_pro.durastep.runtime;

import java.util.Objects;

public record Failure(String type, String message) {

  public Failure {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(message, "message");
  }

  public static Failure of(Throwable error) {
    return new Failure(
        error.getClass().getName(), Objects.requireNonNullElse(error.getMessage(), ""));
  }
}
