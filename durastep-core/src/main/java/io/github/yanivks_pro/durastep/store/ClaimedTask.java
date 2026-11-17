package io.github.yanivks_pro.durastep.store;

public record ClaimedTask(
    long id,
    String workflowId,
    TaskKind kind,
    Integer activitySeq,
    int attempt,
    long fencingToken,
    String owner,
    boolean takenOver) {

  public ClaimedTask(
      long id,
      String workflowId,
      TaskKind kind,
      Integer activitySeq,
      int attempt,
      long fencingToken,
      String owner) {
    this(id, workflowId, kind, activitySeq, attempt, fencingToken, owner, false);
  }
}
