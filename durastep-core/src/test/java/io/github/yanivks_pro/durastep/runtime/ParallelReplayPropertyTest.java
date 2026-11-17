package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ParallelReplayPropertyTest {

  private static final int RUNS = 300;
  private static final Instant START = Instant.parse("2026-11-16T10:00:00Z");

  interface Steps {
    String step(String branch, int number);
  }

  private static final WorkflowDefinition<String, String> TRIP =
      new WorkflowDefinition<>(
          "Trip",
          String.class,
          () ->
              (ctx, trip) -> {
                Steps steps = ctx.activities(Steps.class);
                Promise<String> a =
                    ctx.async(() -> steps.step("a", 1) + steps.step("a", 2) + steps.step("a", 3));
                Promise<String> b = ctx.async(() -> steps.step("b", 1) + steps.step("b", 2));
                Promise<String> c =
                    ctx.async(
                        () -> {
                          String first = steps.step("c", 1);
                          Promise<String> inner = ctx.async(() -> steps.step("inner", 1));
                          return first + steps.step("c", 2) + inner.get();
                        });
                Promise<String> x = ctx.async(() -> steps.step("x", 1));
                Promise<String> y = ctx.async(() -> steps.step("y", 1));
                String quote = Promise.anyOf(x, y).get();
                Promise.allOf(a, b, c).get();
                String quoteAskedLater = Promise.anyOf(y, x).get();
                return a.get()
                    + "|"
                    + b.get()
                    + "|"
                    + c.get()
                    + "|"
                    + quote
                    + "|"
                    + quoteAskedLater;
              });

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  record Decision(int historySize, List<Command> commands) {}

  static Stream<Long> seeds() {
    return IntStream.range(0, RUNS).mapToObj(run -> 1_000L + run);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void everyCompletionOrderGivesTheSameResultAndReplaysToTheSameDecisions(long seed) {
    Random random = new Random(seed);
    List<HistoryEntry> history = new ArrayList<>();
    append(history, new WorkflowStarted(json.toPayload("trip-1")));
    List<Decision> decisions = new ArrayList<>();
    Map<Integer, String> pending = new LinkedHashMap<>();
    Set<String> scheduled = new HashSet<>();
    CompleteWorkflow completed = null;

    while (completed == null) {
      List<Command> commands = runtime.decide(TRIP, "trip-1", List.copyOf(history));
      decisions.add(new Decision(history.size(), commands));
      for (Command command : commands) {
        switch (command) {
          case ScheduleActivity schedule -> {
            int seq = append(history, scheduledEvent(schedule));
            String result = resultOf(schedule.arguments());
            assertThat(scheduled.add(result)).as("%s scheduled twice", result).isTrue();
            pending.put(seq, result);
          }
          case CompleteWorkflow complete -> completed = complete;
          default -> throw new AssertionError("unexpected command " + command);
        }
      }
      if (completed == null) {
        assertThat(pending).as("waiting with nothing pending").isNotEmpty();
        int results = 1 + random.nextInt(Math.min(3, pending.size()));
        for (int i = 0; i < results; i++) {
          List<Integer> open = List.copyOf(pending.keySet());
          int seq = open.get(random.nextInt(open.size()));
          append(history, new ActivityCompleted(seq, json.toPayload(pending.remove(seq))));
        }
      }
    }

    assertThat(json.fromPayload(completed.result(), String.class))
        .isEqualTo("a1a2a3|b1b2|c1c2inner1|" + firstQuoteIn(history) + "|" + firstQuoteIn(history));
    for (Decision decision : decisions) {
      assertThat(runtime.decide(TRIP, "trip-1", history.subList(0, decision.historySize())))
          .as("replay of the first %d events", decision.historySize())
          .isEqualTo(decision.commands());
    }
  }

  private String firstQuoteIn(List<HistoryEntry> history) {
    for (HistoryEntry entry : history) {
      if (entry.event() instanceof ActivityCompleted done) {
        String result = (String) json.fromPayload(done.result(), String.class);
        if (result.equals("x1") || result.equals("y1")) {
          return result;
        }
      }
    }
    throw new AssertionError("no quote completed");
  }

  private static ActivityScheduled scheduledEvent(ScheduleActivity schedule) {
    return new ActivityScheduled(
        schedule.activity(), schedule.arguments(), schedule.options(), schedule.branch());
  }

  private static String resultOf(Payload arguments) {
    return arguments.json().replaceAll("[\\[\\]\",]", "");
  }

  private static int append(List<HistoryEntry> history, HistoryEvent event) {
    int seq = history.size() + 1;
    history.add(new HistoryEntry(seq, START.plusSeconds(seq), event));
    return seq;
  }
}
