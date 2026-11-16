package io.github.yanivks_pro.durastep.api;

/**
 * What a workflow is doing now, returned by {@link WorkflowHandle#describe()}.
 *
 * @param id the workflow's business ID
 * @param type the workflow's type, its class's simple name
 * @param state the workflow's state
 * @param status the status last published with {@link WorkflowContext#setStatus}, or {@code null}
 *     if none was published
 * @param blocked whether the workflow is blocked by an unexpected error until its code is fixed
 */
public record WorkflowDescription(
    String id, String type, WorkflowState state, String status, boolean blocked) {}
