package io.github.yanivks_pro.durastep.history;

import io.github.yanivks_pro.durastep.api.HistoryRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Timeline {

  public enum Outcome {
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    STOPPED
  }

  public record Entry(int seq, String type, Instant at, String text) {}

  public record Step(int seq, String name, Instant startedAt, Instant endedAt, Outcome outcome) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<Entry> entries;
  private final List<Step> steps;

  private Timeline(List<Entry> entries, List<Step> steps) {
    this.entries = List.copyOf(entries);
    this.steps = List.copyOf(steps);
  }

  public static Timeline of(List<HistoryRecord> events) {
    Builder builder = new Builder();
    events.forEach(builder::add);
    return builder.build();
  }

  public List<Entry> entries() {
    return entries;
  }

  public List<Step> steps() {
    return steps;
  }

  private static final class Builder {
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, Step> steps = new LinkedHashMap<>();
    private final Map<Integer, String> labels = new HashMap<>();
    private final Map<Integer, String> signalWaits = new HashMap<>();

    void add(HistoryRecord event) {
      entries.add(new Entry(event.seq(), event.type(), event.recordedAt(), describe(event)));
    }

    Timeline build() {
      return new Timeline(entries, new ArrayList<>(steps.values()));
    }

    private String describe(HistoryRecord event) {
      return switch (event.type()) {
        case "WORKFLOW_STARTED" -> "started";
        case "ACTIVITY_SCHEDULED" -> {
          JsonNode data = parse(event);
          String activity = data.path("activity").asString();
          open(event, activity, branch(data));
          yield "scheduled " + activity + branch(data);
        }
        case "ACTIVITY_COMPLETED" -> {
          int scheduled = parse(event).path("scheduledSeq").asInt();
          close(scheduled, event.recordedAt(), Outcome.COMPLETED);
          yield name(scheduled) + " completed";
        }
        case "ACTIVITY_FAILED" -> {
          JsonNode data = parse(event);
          int scheduled = data.path("scheduledSeq").asInt();
          close(scheduled, event.recordedAt(), Outcome.FAILED);
          yield name(scheduled)
              + " failed after "
              + attempts(data.path("attempts").asInt(1))
              + ": "
              + data.path("failure").path("message").asString();
        }
        case "TIMER_STARTED" -> {
          JsonNode data = parse(event);
          String duration = data.path("duration").asString();
          String name;
          if (data.has("signal")) {
            String signal = data.path("signal").asString();
            signalWaits.put(event.seq(), signal);
            name = "waiting for signal " + signal + " up to " + duration;
          } else {
            name = "sleeping for " + duration;
          }
          open(event, name, branch(data));
          yield name + branch(data);
        }
        case "TIMER_FIRED" -> {
          int started = parse(event).path("startedSeq").asInt();
          if (started == 0) {
            yield "deadline passed";
          }
          boolean signalWait = signalWaits.remove(started) != null;
          close(started, event.recordedAt(), signalWait ? Outcome.TIMED_OUT : Outcome.COMPLETED);
          yield "woke up from #" + started;
        }
        case "SIGNAL" -> {
          String signal = parse(event).path("name").asString();
          endWaitFor(signal, event.recordedAt());
          yield "received signal " + signal;
        }
        case "CANCEL_REQUESTED" -> "asked to cancel";
        case "VERSION_MARKER" -> {
          JsonNode data = parse(event);
          yield "uses version "
              + data.path("version").asInt()
              + " of "
              + data.path("changeId").asString();
        }
        case "OPERATOR_ACTION" -> {
          JsonNode data = parse(event);
          yield data.path("action").asString()
              + " by "
              + data.path("by").asString()
              + ": "
              + data.path("reason").asString();
        }
        case "WORKFLOW_COMPLETED" -> {
          stopAll(event.recordedAt());
          yield "completed";
        }
        case "WORKFLOW_FAILED" -> {
          stopAll(event.recordedAt());
          yield "failed: " + parse(event).path("message").asString();
        }
        case "WORKFLOW_CANCELLED" -> {
          stopAll(event.recordedAt());
          yield "cancelled";
        }
        default -> event.type();
      };
    }

    private void open(HistoryRecord event, String label, String branch) {
      labels.put(event.seq(), label);
      steps.put(
          event.seq(),
          new Step(event.seq(), label + branch, event.recordedAt(), null, Outcome.RUNNING));
    }

    private void close(int seq, Instant at, Outcome outcome) {
      Step step = steps.get(seq);
      if (step != null && step.outcome() == Outcome.RUNNING) {
        steps.put(seq, new Step(seq, step.name(), step.startedAt(), at, outcome));
      }
    }

    private void endWaitFor(String signal, Instant at) {
      Iterator<Map.Entry<Integer, String>> waits = signalWaits.entrySet().iterator();
      while (waits.hasNext()) {
        Map.Entry<Integer, String> wait = waits.next();
        if (wait.getValue().equals(signal)) {
          close(wait.getKey(), at, Outcome.COMPLETED);
          waits.remove();
        }
      }
    }

    private void stopAll(Instant at) {
      for (Step step : List.copyOf(steps.values())) {
        close(step.seq(), at, Outcome.STOPPED);
      }
    }

    private String name(int seq) {
      return labels.getOrDefault(seq, "step #" + seq);
    }

    private static JsonNode parse(HistoryRecord event) {
      return JSON.readTree(event.json());
    }

    private static String branch(JsonNode data) {
      return data.has("branch") ? " (branch " + data.path("branch").asString() + ")" : "";
    }

    private static String attempts(int attempts) {
      return attempts == 1 ? "1 attempt" : attempts + " attempts";
    }
  }
}
