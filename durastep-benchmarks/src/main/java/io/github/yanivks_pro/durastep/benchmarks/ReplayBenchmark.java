package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import io.github.yanivks_pro.durastep.runtime.WorkflowDefinition;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ReplayBenchmark {

  public interface Counter {
    int next(int step);
  }

  public static final class Steps implements Workflow<Integer, Integer> {
    @Override
    public Integer run(WorkflowContext ctx, Integer steps) {
      Counter counter = ctx.activities(Counter.class);
      int total = 0;
      for (int i = 0; i < steps; i++) {
        total += counter.next(i);
      }
      return total;
    }
  }

  @Param({"10", "100", "1000", "10000"})
  public int steps;

  private final JsonPayloadConverter converter = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(converter);
  private final WorkflowDefinition<Integer, Integer> definition =
      WorkflowDefinition.of(Steps.class, Steps::new);
  private List<HistoryEntry> history;

  @Setup
  public void buildAFinishedHistory() {
    history = historyOf(steps);
    if (!(runtime.decide(definition, "replay", history).getFirst() instanceof CompleteWorkflow)) {
      throw new IllegalStateException("the history does not replay to a finished workflow");
    }
  }

  @Benchmark
  public List<Command> replayTheWholeHistory() {
    return runtime.decide(definition, "replay", history);
  }

  List<HistoryEntry> historyOf(int count) {
    Instant at = Instant.EPOCH;
    List<HistoryEntry> entries = new ArrayList<>();
    entries.add(new HistoryEntry(1, at, new WorkflowStarted(converter.toPayload(count))));
    ScheduleActivity step =
        (ScheduleActivity) runtime.decide(definition, "replay", entries).getFirst();
    for (int i = 0; i < count; i++) {
      int scheduled = entries.size() + 1;
      entries.add(
          new HistoryEntry(
              scheduled,
              at,
              new ActivityScheduled(
                  step.activity(), step.arguments(), step.options(), step.branch())));
      entries.add(
          new HistoryEntry(
              scheduled + 1, at, new ActivityCompleted(scheduled, converter.toPayload(1))));
    }
    return List.copyOf(entries);
  }
}
