package io.github.yanivks_pro.durastep.store;

public record ClaimedTask(
    long id,
    String workflowId,
    TaskKind kind,
    Integer activitySeq,
    long fencingToken,
    String owner) {}
