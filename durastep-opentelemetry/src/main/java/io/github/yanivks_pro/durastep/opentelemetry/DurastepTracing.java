package io.github.yanivks_pro.durastep.opentelemetry;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.ActivityTrace;
import io.github.yanivks_pro.durastep.api.TraceTarget;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.api.WorkflowTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Traces Durastep workflows with OpenTelemetry: every activity attempt, timer, signal and the end
 * of a workflow become spans in the trace of the code that started it, whichever engine runs them
 * and however much later.
 *
 * <pre>{@code
 * WorkflowTracing tracing = DurastepTracing.of(openTelemetry);
 * DurastepEngine.builder().dataSource(dataSource).tracing(tracing)...
 * DurastepClient.connect(dataSource).withTracing(tracing);
 * }</pre>
 *
 * <p>Spans are named {@code activity Payments.charge}, {@code timer sleep PT2H}, {@code signal
 * approve} and {@code workflow OrderWorkflow}, and carry the workflow's ID and type. Inside an
 * activity, its span is current, so calls the activity makes join the trace. A workflow started
 * where no span is current gets a short {@code start OrderWorkflow} span as the root of its trace.
 * Spans never carry workflow data: a failed attempt records the error's type, not its message.
 */
public final class DurastepTracing implements WorkflowTracing {

  /** The instrumentation scope name of Durastep's spans. */
  public static final String INSTRUMENTATION_NAME = "io.github.yanivks-pro.durastep";

  static final AttributeKey<String> WORKFLOW_ID = AttributeKey.stringKey("durastep.workflow.id");
  static final AttributeKey<String> WORKFLOW_TYPE =
      AttributeKey.stringKey("durastep.workflow.type");
  static final AttributeKey<String> WORKFLOW_STATE =
      AttributeKey.stringKey("durastep.workflow.state");
  static final AttributeKey<Long> ACTIVITY_ATTEMPT =
      AttributeKey.longKey("durastep.activity.attempt");
  static final AttributeKey<String> ACTIVITY_OUTCOME =
      AttributeKey.stringKey("durastep.activity.outcome");
  static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

  private static final TextMapGetter<Map<String, String>> FROM_MAP =
      (carrier, key) -> carrier == null ? null : carrier.get(key);

  private final Tracer tracer;
  private final TextMapPropagator propagator;

  private DurastepTracing(Tracer tracer, TextMapPropagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * Returns tracing that uses the given OpenTelemetry's tracer and propagators.
   *
   * @param openTelemetry the application's OpenTelemetry
   * @return the tracing, to pass to the engine and to clients
   */
  public static DurastepTracing of(OpenTelemetry openTelemetry) {
    Objects.requireNonNull(openTelemetry, "openTelemetry");
    return new DurastepTracing(
        openTelemetry.getTracer(INSTRUMENTATION_NAME),
        openTelemetry.getPropagators().getTextMapPropagator());
  }

  @Override
  public Map<String, String> capture(String workflowId, String workflowType) {
    Context context = Context.current();
    if (!Span.fromContext(context).getSpanContext().isValid()) {
      Span start =
          tracer
              .spanBuilder("start " + workflowType)
              .setNoParent()
              .setAttribute(WORKFLOW_ID, workflowId)
              .setAttribute(WORKFLOW_TYPE, workflowType)
              .startSpan();
      start.end();
      context = context.with(start);
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(context, carrier, (map, key, value) -> map.put(key, value));
    return carrier;
  }

  @Override
  public ActivityTrace activityStarted(TraceTarget target) {
    Context parent = parentOf(target);
    Span span =
        span("activity " + target.name(), target, parent)
            .setAttribute(ACTIVITY_ATTEMPT, (long) target.attempt())
            .startSpan();
    Context inside = parent.with(span);
    return new ActivityTrace() {
      @Override
      public <T> Callable<T> wrap(Callable<T> attempt) {
        return inside.wrap(attempt);
      }

      @Override
      public void end(ActivityOutcome outcome, String failureType) {
        span.setAttribute(ACTIVITY_OUTCOME, outcome.name().toLowerCase(Locale.ROOT));
        if (outcome != ActivityOutcome.SUCCEEDED) {
          span.setStatus(StatusCode.ERROR);
          if (failureType != null) {
            span.setAttribute(ERROR_TYPE, failureType);
          }
        }
        span.end();
      }
    };
  }

  @Override
  public void timerFired(TraceTarget target, Instant startedAt, Instant firedAt) {
    span("timer " + target.name(), target, parentOf(target))
        .setStartTimestamp(startedAt)
        .startSpan()
        .end(firedAt);
  }

  @Override
  public void signalReceived(TraceTarget target, Instant receivedAt) {
    span("signal " + target.name(), target, parentOf(target))
        .setStartTimestamp(receivedAt)
        .startSpan()
        .end(receivedAt);
  }

  @Override
  public void workflowFinished(
      TraceTarget target, WorkflowState state, Instant startedAt, Instant closedAt) {
    Span span =
        span("workflow " + target.workflowType(), target, parentOf(target))
            .setAttribute(WORKFLOW_STATE, state.name().toLowerCase(Locale.ROOT))
            .setStartTimestamp(startedAt)
            .startSpan();
    if (state == WorkflowState.FAILED) {
      span.setStatus(StatusCode.ERROR);
    }
    span.end(closedAt);
  }

  private SpanBuilder span(String name, TraceTarget target, Context parent) {
    return tracer
        .spanBuilder(name)
        .setParent(parent)
        .setAttribute(WORKFLOW_ID, target.workflowId())
        .setAttribute(WORKFLOW_TYPE, target.workflowType());
  }

  private Context parentOf(TraceTarget target) {
    return target.context().isEmpty()
        ? Context.root()
        : propagator.extract(Context.root(), target.context(), FROM_MAP);
  }
}
