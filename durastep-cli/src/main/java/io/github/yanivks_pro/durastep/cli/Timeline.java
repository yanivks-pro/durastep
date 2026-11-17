package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.api.HistoryRecord;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class Timeline {

  private final Map<Integer, String> stepNames = new HashMap<>();

  String describe(HistoryRecord event) {
    JsonNode data = Output.parse(event.json());
    return switch (event.type()) {
      case "WORKFLOW_STARTED" -> "started";
      case "ACTIVITY_SCHEDULED" -> {
        String activity = data.path("activity").asString();
        stepNames.put(event.seq(), activity);
        yield "scheduled " + activity + branch(data);
      }
      case "ACTIVITY_COMPLETED" -> step(data.path("scheduledSeq").asInt()) + " completed";
      case "ACTIVITY_FAILED" ->
          step(data.path("scheduledSeq").asInt())
              + " failed after "
              + attempts(data.path("attempts").asInt(1))
              + ": "
              + data.path("failure").path("message").asString();
      case "TIMER_STARTED" -> {
        String duration = data.path("duration").asString();
        String name =
            data.has("signal")
                ? "waiting for signal " + data.path("signal").asString() + " up to " + duration
                : "sleeping for " + duration;
        stepNames.put(event.seq(), name);
        yield name + branch(data);
      }
      case "TIMER_FIRED" -> {
        int started = data.path("startedSeq").asInt();
        yield started == 0 ? "deadline passed" : "woke up from #" + started;
      }
      case "SIGNAL" -> "received signal " + data.path("name").asString();
      case "CANCEL_REQUESTED" -> "asked to cancel";
      case "VERSION_MARKER" ->
          "uses version "
              + data.path("version").asInt()
              + " of "
              + data.path("changeId").asString();
      case "OPERATOR_ACTION" ->
          data.path("action").asString()
              + " by "
              + data.path("by").asString()
              + ": "
              + data.path("reason").asString();
      case "WORKFLOW_COMPLETED" -> "completed";
      case "WORKFLOW_FAILED" -> "failed: " + data.path("message").asString();
      case "WORKFLOW_CANCELLED" -> "cancelled";
      default -> event.type();
    };
  }

  static void print(Io io, List<HistoryRecord> events) {
    if (events.isEmpty()) {
      return;
    }
    Timeline timeline = new Timeline();
    for (HistoryRecord event : events) {
      Duration offset = Duration.between(events.getFirst().recordedAt(), event.recordedAt());
      io.out()
          .printf(
              "  #%-3d %8s  %s%n",
              event.seq(),
              "+" + Output.seconds(offset).replace(" s", "s"),
              timeline.describe(event));
    }
  }

  private String step(int seq) {
    return stepNames.getOrDefault(seq, "step #" + seq);
  }

  private static String branch(JsonNode data) {
    return data.has("branch") ? " (branch " + data.path("branch").asString() + ")" : "";
  }

  private static String attempts(int attempts) {
    return attempts == 1 ? "1 attempt" : attempts + " attempts";
  }
}
