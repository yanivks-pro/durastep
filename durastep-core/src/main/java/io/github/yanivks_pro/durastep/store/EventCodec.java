package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class EventCodec {

  static final String WORKFLOW_STARTED = "WORKFLOW_STARTED";
  static final String ACTIVITY_SCHEDULED = "ACTIVITY_SCHEDULED";
  static final String ACTIVITY_COMPLETED = "ACTIVITY_COMPLETED";
  static final String ACTIVITY_FAILED = "ACTIVITY_FAILED";
  static final String TIMER_STARTED = "TIMER_STARTED";
  static final String TIMER_FIRED = "TIMER_FIRED";
  static final String WORKFLOW_COMPLETED = "WORKFLOW_COMPLETED";
  static final String WORKFLOW_FAILED = "WORKFLOW_FAILED";

  private final JsonMapper mapper = JsonMapper.builder().build();

  String typeOf(HistoryEvent event) {
    return switch (event) {
      case WorkflowStarted ignored -> WORKFLOW_STARTED;
      case ActivityScheduled ignored -> ACTIVITY_SCHEDULED;
      case ActivityCompleted ignored -> ACTIVITY_COMPLETED;
      case ActivityFailed ignored -> ACTIVITY_FAILED;
      case TimerStarted ignored -> TIMER_STARTED;
      case TimerFired ignored -> TIMER_FIRED;
      case WorkflowCompleted ignored -> WORKFLOW_COMPLETED;
      case WorkflowFailed ignored -> WORKFLOW_FAILED;
    };
  }

  String payloadOf(HistoryEvent event) {
    return switch (event) {
      case WorkflowStarted started -> started.input().json();
      case ActivityScheduled scheduled ->
          object()
              .put("activity", scheduled.activity())
              .set("arguments", tree(scheduled.arguments()))
              .set("options", optionsTree(scheduled.options()))
              .toString();
      case ActivityCompleted completed ->
          object()
              .put("scheduledSeq", completed.scheduledSeq())
              .set("result", tree(completed.result()))
              .toString();
      case ActivityFailed failed ->
          object()
              .put("scheduledSeq", failed.scheduledSeq())
              .put("attempts", failed.attempts())
              .set("failure", failureTree(failed.failure()))
              .toString();
      case TimerStarted started ->
          object().put("duration", started.duration().toString()).toString();
      case TimerFired fired -> object().put("startedSeq", fired.startedSeq()).toString();
      case WorkflowCompleted completed -> completed.result().json();
      case WorkflowFailed failed -> failureJson(failed.failure());
    };
  }

  HistoryEvent decode(String type, String payloadJson) {
    return switch (type) {
      case WORKFLOW_STARTED -> new WorkflowStarted(new Payload(payloadJson));
      case ACTIVITY_SCHEDULED -> {
        JsonNode node = mapper.readTree(payloadJson);
        yield new ActivityScheduled(
            node.required("activity").asString(),
            payload(node.required("arguments")),
            node.has("options") ? options(node.get("options")) : ActivityOptions.DEFAULT);
      }
      case ACTIVITY_COMPLETED -> {
        JsonNode node = mapper.readTree(payloadJson);
        yield new ActivityCompleted(
            node.required("scheduledSeq").asInt(), payload(node.required("result")));
      }
      case ACTIVITY_FAILED -> {
        JsonNode node = mapper.readTree(payloadJson);
        yield new ActivityFailed(
            node.required("scheduledSeq").asInt(),
            failure(node.required("failure")),
            node.has("attempts") ? node.get("attempts").asInt() : 1);
      }
      case TIMER_STARTED ->
          new TimerStarted(
              Duration.parse(mapper.readTree(payloadJson).required("duration").asString()));
      case TIMER_FIRED ->
          new TimerFired(mapper.readTree(payloadJson).required("startedSeq").asInt());
      case WORKFLOW_COMPLETED -> new WorkflowCompleted(new Payload(payloadJson));
      case WORKFLOW_FAILED -> new WorkflowFailed(failure(mapper.readTree(payloadJson)));
      default -> throw new IllegalArgumentException("unknown event type " + type);
    };
  }

  String failureJson(Failure failure) {
    return failureTree(failure).toString();
  }

  Failure failure(String json) {
    return failure(mapper.readTree(json));
  }

  private ObjectNode optionsTree(ActivityOptions options) {
    RetryPolicy retry = options.retryPolicy();
    ObjectNode node = object().put("attemptTimeout", options.attemptTimeout().toString());
    options.totalTimeout().ifPresent(total -> node.put("totalTimeout", total.toString()));
    ArrayNode nonRetryable = mapper.createArrayNode();
    retry.nonRetryableTypes().forEach(nonRetryable::add);
    node.set(
        "retry",
        object()
            .put("maxAttempts", retry.maxAttempts())
            .put("initialBackoff", retry.initialBackoff().toString())
            .put("maxBackoff", retry.maxBackoff().toString())
            .set("nonRetryable", nonRetryable));
    return node;
  }

  private static ActivityOptions options(JsonNode node) {
    JsonNode retry = node.required("retry");
    List<String> nonRetryable = new ArrayList<>();
    retry.required("nonRetryable").forEach(type -> nonRetryable.add(type.asString()));
    ActivityOptions.Builder builder =
        ActivityOptions.builder()
            .attemptTimeout(Duration.parse(node.required("attemptTimeout").asString()))
            .retryPolicy(
                new RetryPolicy(
                    retry.required("maxAttempts").asInt(),
                    Duration.parse(retry.required("initialBackoff").asString()),
                    Duration.parse(retry.required("maxBackoff").asString()),
                    nonRetryable));
    if (node.has("totalTimeout")) {
      builder.totalTimeout(Duration.parse(node.get("totalTimeout").asString()));
    }
    return builder.build();
  }

  private ObjectNode object() {
    return mapper.createObjectNode();
  }

  private ObjectNode failureTree(Failure failure) {
    return object().put("type", failure.type()).put("message", failure.message());
  }

  private static Failure failure(JsonNode node) {
    return new Failure(node.required("type").asString(), node.required("message").asString());
  }

  private JsonNode tree(Payload payload) {
    return mapper.readTree(payload.json());
  }

  private Payload payload(JsonNode node) {
    return new Payload(mapper.writeValueAsString(node));
  }
}
