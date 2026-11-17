package io.github.yanivks_pro.durastep.api;

import java.time.Instant;

/**
 * A snapshot of a workflow, as {@link WorkflowHandle#describe()} and {@link DurastepClient#list}
 * return it.
 *
 * @param id the workflow's business ID
 * @param type the workflow type name
 * @param state the workflow's state
 * @param status the status the workflow last published with {@link WorkflowContext#setStatus}, or
 *     {@code null}
 * @param blocked whether the workflow is blocked by an error and waiting for a fix
 * @param startedAt when the workflow was started
 * @param closedAt when the workflow finished, or {@code null} while it runs
 */
public record WorkflowDescription(
    String id,
    String type,
    WorkflowState state,
    String status,
    boolean blocked,
    Instant startedAt,
    Instant closedAt) {}
