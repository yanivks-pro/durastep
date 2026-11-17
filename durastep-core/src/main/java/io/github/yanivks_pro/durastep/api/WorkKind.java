package io.github.yanivks_pro.durastep.api;

/** The two kinds of work an engine claims, as reported to an {@link EngineListener}. */
public enum WorkKind {
  /** Deciding a workflow's next steps by replaying its history. */
  DECISION,
  /** Running an activity. */
  ACTIVITY
}
