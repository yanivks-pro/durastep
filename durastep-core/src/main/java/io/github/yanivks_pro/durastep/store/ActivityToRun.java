package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.Payload;
import java.util.Map;

public record ActivityToRun(
    String workflowId,
    String workflowType,
    Map<String, String> traceContext,
    int scheduledSeq,
    String activity,
    Payload arguments,
    ActivityOptions options,
    int attempt) {}
