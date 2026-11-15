package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import java.time.Instant;

public record InboxEntry(long id, Instant receivedAt, HistoryEvent event) {}
