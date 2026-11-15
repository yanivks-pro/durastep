package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;

public record StoredWorkflow(
    String id, String type, WorkflowState state, Payload result, Failure failure) {}
