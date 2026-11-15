package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class EventCodec {

  static final String WORKFLOW_STARTED = "WORKFLOW_STARTED";
  static final String ACTIVITY_SCHEDULED = "ACTIVITY_SCHEDULED";
  static final String ACTIVITY_COMPLETED = "ACTIVITY_COMPLETED";
  static final String ACTIVITY_FAILED = "ACTIVITY_FAILED";
  static final String WORKFLOW_COMPLETED = "WORKFLOW_COMPLETED";
  static final String WORKFLOW_FAILED = "WORKFLOW_FAILED";

  private final JsonMapper mapper = JsonMapper.builder().build();

  String typeOf(HistoryEvent event) {
    return switch (event) {
      case WorkflowStarted ignored -> WORKFLOW_STARTED;
      case ActivityScheduled ignored -> ACTIVITY_SCHEDULED;
      case ActivityCompleted ignored -> ACTIVITY_COMPLETED;
      case ActivityFailed ignored -> ACTIVITY_FAILED;
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
              .toString();
      case ActivityCompleted completed ->
          object()
              .put("scheduledSeq", completed.scheduledSeq())
              .set("result", tree(completed.result()))
              .toString();
      case ActivityFailed failed ->
          object()
              .put("scheduledSeq", failed.scheduledSeq())
              .set("failure", failureTree(failed.failure()))
              .toString();
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
            node.required("activity").asString(), payload(node.required("arguments")));
      }
      case ACTIVITY_COMPLETED -> {
        JsonNode node = mapper.readTree(payloadJson);
        yield new ActivityCompleted(
            node.required("scheduledSeq").asInt(), payload(node.required("result")));
      }
      case ACTIVITY_FAILED -> {
        JsonNode node = mapper.readTree(payloadJson);
        yield new ActivityFailed(
            node.required("scheduledSeq").asInt(), failure(node.required("failure")));
      }
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
