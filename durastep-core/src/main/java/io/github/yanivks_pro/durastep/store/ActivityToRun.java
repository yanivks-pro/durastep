package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.Payload;

public record ActivityToRun(
    String workflowId,
    int scheduledSeq,
    String activity,
    Payload arguments,
    ActivityOptions options,
    int attempt) {}
