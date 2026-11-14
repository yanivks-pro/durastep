package io.github.yanivks_pro.durastep.runtime;

import java.time.Instant;
import java.util.Objects;

public record HistoryEntry(int seq, Instant recordedAt, HistoryEvent event) {

  public HistoryEntry {
    if (seq < 1) {
      throw new IllegalArgumentException("seq must be at least 1, was " + seq);
    }
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(event, "event");
  }
}
