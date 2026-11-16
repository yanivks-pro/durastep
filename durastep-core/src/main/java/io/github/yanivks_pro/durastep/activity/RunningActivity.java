package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityLeaseLostException;
import java.time.Duration;
import java.util.function.LongSupplier;

final class RunningActivity implements ActivityContext {

  private final String workflowId;
  private final int scheduledSeq;
  private final Heartbeat heartbeat;
  private final boolean heartbeating;
  private final long minimumGapNanos;
  private final LongSupplier clock;
  private long lastBeatNanos;

  RunningActivity(String workflowId, int scheduledSeq) {
    this.workflowId = workflowId;
    this.scheduledSeq = scheduledSeq;
    this.heartbeat = Heartbeat.NONE;
    this.heartbeating = false;
    this.minimumGapNanos = 0;
    this.clock = System::nanoTime;
  }

  RunningActivity(
      String workflowId,
      int scheduledSeq,
      Heartbeat heartbeat,
      Duration heartbeatTimeout,
      LongSupplier clock) {
    this.workflowId = workflowId;
    this.scheduledSeq = scheduledSeq;
    this.heartbeat = heartbeat;
    this.heartbeating = true;
    this.minimumGapNanos = heartbeatTimeout.toNanos() / 3;
    this.clock = clock;
    this.lastBeatNanos = clock.getAsLong();
  }

  @Override
  public String workflowId() {
    return workflowId;
  }

  @Override
  public String idempotencyKey() {
    return workflowId + "/activity-" + scheduledSeq;
  }

  @Override
  public synchronized void heartbeat() {
    if (!heartbeating) {
      return;
    }
    long now = clock.getAsLong();
    if (now - lastBeatNanos < minimumGapNanos) {
      return;
    }
    lastBeatNanos = now;
    if (!heartbeat.beat()) {
      throw new ActivityLeaseLostException(idempotencyKey());
    }
  }
}
