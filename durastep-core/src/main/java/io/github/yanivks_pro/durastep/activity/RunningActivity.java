package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.api.ActivityContext;

record RunningActivity(String workflowId, int scheduledSeq) implements ActivityContext {

  @Override
  public String idempotencyKey() {
    return workflowId + "/activity-" + scheduledSeq;
  }
}
