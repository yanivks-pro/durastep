package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import java.time.Instant;

public final class HistoryDecoder {

  private static final EventCodec CODEC = new EventCodec();

  private HistoryDecoder() {}

  public static HistoryEntry decode(int seq, Instant recordedAt, String type, String json) {
    return new HistoryEntry(seq, recordedAt, CODEC.decode(type, json));
  }
}
