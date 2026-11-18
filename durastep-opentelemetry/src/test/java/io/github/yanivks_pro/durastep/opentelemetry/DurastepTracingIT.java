package io.github.yanivks_pro.durastep.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DurastepTracingIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  interface Payments {
    String charge(String order);
  }

  static final class Psp implements Payments {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> currentSpan = new AtomicReference<>();

    @Override
    public String charge(String order) {
      if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("card 4111 was declined by the gateway");
      }
      currentSpan.set(Span.current().getSpanContext().getSpanId());
      Span call = tracer.spanBuilder("POST https://psp.example/charge").startSpan();
      call.end();
      return "ch_" + order;
    }
  }

  static final class Order implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      String charge =
          ctx.activities(
                  Payments.class,
                  ActivityOptions.builder()
                      .retryPolicy(
                          RetryPolicy.builder().initialBackoff(Duration.ofMillis(50)).build())
                      .build())
              .charge(order);
      ctx.sleep(Duration.ofMillis(150));
      ctx.awaitSignal("approve", String.class);
      return charge;
    }
  }

  static final class Refused implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      throw new WorkflowFailedException("refused");
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;
  private static final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private static OpenTelemetrySdk openTelemetry;
  private static Tracer tracer;

  @BeforeAll
  static void start() {
    postgres = new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:16-alpine"));
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    tracer = openTelemetry.getTracer("test");
  }

  @AfterAll
  static void stop() {
    openTelemetry.close();
    postgres.stop();
  }

  @BeforeEach
  void forgetSpans() {
    spans.reset();
  }

  @Test
  void aWorkflowStartedInARequestIsOneTraceUnderThatRequest() throws Exception {
    Psp psp = new Psp();
    DurastepTracing tracing = DurastepTracing.of(openTelemetry);
    DurastepClient client = DurastepClient.connect(dataSource).withTracing(tracing);
    Span request = tracer.spanBuilder("POST /orders").startSpan();
    WorkflowHandle<String> handle;
    Scope scope = request.makeCurrent();
    try {
      handle = client.start(Order.class, "order-1", "order-1");
    } finally {
      scope.close();
      request.end();
    }
    try (DurastepEngine engine = engine(tracing, psp)) {
      engine.start();
      await().atMost(WAIT).until(() -> named("timer sleep PT0.15S").size() == 1);
      client.signal("order-1", "approve", "yes");
      assertThat(handle.result(WAIT)).isEqualTo("ch_order-1");
      await().atMost(WAIT).until(() -> named("workflow Order").size() == 1);
    }

    String trace = request.getSpanContext().getTraceId();
    String requestId = request.getSpanContext().getSpanId();
    List<SpanData> all = spans.getFinishedSpanItems();
    assertThat(all).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(trace));
    assertThat(all)
        .extracting(SpanData::getName)
        .containsExactlyInAnyOrder(
            "POST /orders",
            "activity Payments.charge",
            "activity Payments.charge",
            "POST https://psp.example/charge",
            "timer sleep PT0.15S",
            "signal approve",
            "workflow Order");

    List<SpanData> attempts = named("activity Payments.charge");
    SpanData first = attempt(attempts, 1);
    SpanData second = attempt(attempts, 2);
    assertThat(List.of(first, second))
        .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(requestId))
        .allSatisfy(
            span ->
                assertThat(span.getAttributes().get(DurastepTracing.WORKFLOW_ID))
                    .isEqualTo("order-1"));
    assertThat(first.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(first.getAttributes().get(DurastepTracing.ACTIVITY_OUTCOME)).isEqualTo("retried");
    assertThat(first.getAttributes().get(DurastepTracing.ERROR_TYPE))
        .isEqualTo("java.lang.IllegalStateException");
    assertThat(second.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    assertThat(second.getAttributes().get(DurastepTracing.ACTIVITY_OUTCOME)).isEqualTo("succeeded");

    assertThat(psp.currentSpan.get()).isEqualTo(second.getSpanId());
    assertThat(named("POST https://psp.example/charge").getFirst().getParentSpanId())
        .isEqualTo(second.getSpanId());

    SpanData timer = named("timer sleep PT0.15S").getFirst();
    assertThat(timer.getParentSpanId()).isEqualTo(requestId);
    assertThat(timer.getEndEpochNanos() - timer.getStartEpochNanos())
        .isGreaterThanOrEqualTo(Duration.ofMillis(150).toNanos());
    SpanData workflow = named("workflow Order").getFirst();
    assertThat(workflow.getAttributes().get(DurastepTracing.WORKFLOW_STATE)).isEqualTo("completed");
    assertThat(workflow.getStartEpochNanos()).isLessThan(first.getStartEpochNanos());
    assertThat(all.toString()).doesNotContain("4111", "declined");
  }

  @Test
  void aWorkflowStartedOutsideATraceGetsAStartSpanAsItsRoot() throws Exception {
    DurastepTracing tracing = DurastepTracing.of(openTelemetry);
    try (DurastepEngine engine = engine(tracing, new Psp())) {
      engine.start();
      WorkflowHandle<String> refused =
          engine.client().start(Refused.class, "refused-1", "refused-1");
      await().atMost(WAIT).until(() -> named("workflow Refused").size() == 1);
      assertThat(refused.describe().state().name()).isEqualTo("FAILED");
    }

    SpanData root = named("start Refused").getFirst();
    SpanData workflow = named("workflow Refused").getFirst();
    assertThat(root.getParentSpanContext().isValid()).isFalse();
    assertThat(workflow.getTraceId()).isEqualTo(root.getTraceId());
    assertThat(workflow.getParentSpanId()).isEqualTo(root.getSpanId());
    assertThat(workflow.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(workflow.getAttributes().get(DurastepTracing.WORKFLOW_STATE)).isEqualTo("failed");
  }

  @Test
  void aWorkflowStartedWithoutTracingIsStillTracedStepByStep() throws Exception {
    DurastepTracing tracing = DurastepTracing.of(openTelemetry);
    try (DurastepEngine engine = engine(tracing, new Psp())) {
      engine.start();
      DurastepClient.connect(dataSource).start(Refused.class, "untraced-1", "x");
      await().atMost(WAIT).until(() -> named("workflow Refused").size() == 1);
    }
    SpanData workflow = named("workflow Refused").getFirst();
    assertThat(workflow.getParentSpanContext().isValid()).isFalse();
    assertThat(named("start Refused")).isEmpty();
  }

  private static DurastepEngine engine(DurastepTracing tracing, Payments payments) {
    return DurastepEngine.builder()
        .dataSource(dataSource)
        .workflow(Order.class, Order::new)
        .workflow(Refused.class, Refused::new)
        .activities(Payments.class, payments)
        .tracing(tracing)
        .build();
  }

  private static List<SpanData> named(String name) {
    return spans.getFinishedSpanItems().stream()
        .filter(span -> span.getName().equals(name))
        .toList();
  }

  private static SpanData attempt(List<SpanData> attempts, long number) {
    return attempts.stream()
        .filter(span -> span.getAttributes().get(DurastepTracing.ACTIVITY_ATTEMPT) == number)
        .findFirst()
        .orElseThrow();
  }
}
