package io.github.yanivks_pro.durastep.payload;

import java.util.Objects;

public record Payload(String json) {

  public Payload {
    Objects.requireNonNull(json, "json");
  }
}
