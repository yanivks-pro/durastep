package io.github.yanivks_pro.durastep.api;

import java.util.Map;
import java.util.Objects;

/**
 * What a {@link WorkflowTracing} call is about: the workflow, the step and the trace context that
 * was captured when the workflow started.
 *
 * @param context the trace context captured by {@link WorkflowTracing#capture}, empty if none
 * @param workflowId the workflow's ID
 * @param workflowType the workflow's type name
 * @param name what is traced, such as {@code Payments.charge}, {@code sleep PT2H} or a signal's
 *     name; the workflow type for {@link WorkflowTracing#workflowFinished}
 * @param attempt the attempt number of an activity, starting at 1; 0 for anything else
 */
public record TraceTarget(
    Map<String, String> context, String workflowId, String workflowType, String name, int attempt) {

  /** Copies the context so the target cannot change. */
  public TraceTarget {
    context = Map.copyOf(Objects.requireNonNull(context, "context"));
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(workflowType, "workflowType");
    Objects.requireNonNull(name, "name");
  }
}
