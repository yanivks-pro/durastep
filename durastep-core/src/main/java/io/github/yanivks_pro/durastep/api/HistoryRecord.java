package io.github.yanivks_pro.durastep.api;

import java.time.Instant;

/**
 * One event in a workflow's history, as {@link WorkflowHandle#history()} returns it: for example
 * that an activity was scheduled, that it completed with a result, or that an operator retried the
 * workflow.
 *
 * @param seq the event's position in the history, starting at 1
 * @param type the event's type, such as {@code ACTIVITY_COMPLETED}
 * @param recordedAt when the event was recorded
 * @param json the event's details as JSON
 */
public record HistoryRecord(int seq, String type, Instant recordedAt, String json) {}
