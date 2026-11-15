package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.payload.Payload;

public record ActivityToRun(
    String workflowId, int scheduledSeq, String activity, Payload arguments) {}
