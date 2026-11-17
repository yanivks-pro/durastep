package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.RecordVersion;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.SetStatus;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.CancelRequested;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.SignalReceived;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.VersionMarker;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

final class SimulatedEngine {

  private static final Instant START = Instant.parse("2026-11-17T10:00:00Z");
  private static final int CRASH_CHANCE_PERCENT = 15;
  private static final int MAX_TURNS = 500;

  record Decision(int historySize, List<Command> commands) {}

  record Signal(String name, Object payload) {}

  private final WorkflowDefinition<?, ?> workflow;
  private final String workflowId;
  private final Function<ScheduleActivity, Object> results;
  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private final List<HistoryEntry> history = new ArrayList<>();
  private final List<Decision> decisions = new ArrayList<>();
  private final Map<Integer, ScheduleActivity> pendingActivities = new LinkedHashMap<>();
  private final Set<Integer> pendingTimers = new TreeSet<>();
  private final List<Signal> signalsToSend = new ArrayList<>();
  private boolean cancelAllowed;
  private boolean cancelRequested;
  private int crashedDecisions;
  private Command ending;

  SimulatedEngine(
      WorkflowDefinition<?, ?> workflow,
      String workflowId,
      Object input,
      Function<ScheduleActivity, Object> results) {
    this.workflow = workflow;
    this.workflowId = workflowId;
    this.results = results;
    append(new WorkflowStarted(json.toPayload(input)));
  }

  SimulatedEngine withSignal(String name, Object payload) {
    signalsToSend.add(new Signal(name, payload));
    return this;
  }

  SimulatedEngine allowingCancellation() {
    cancelAllowed = true;
    return this;
  }

  SimulatedEngine run(List<Integer> choices) {
    Iterator<Integer> next = choices.iterator();
    for (int turn = 0; turn < MAX_TURNS && ending == null; turn++) {
      List<Command> commands = runtime.decide(workflow, workflowId, List.copyOf(history));
      assertThat(runtime.decide(workflow, workflowId, List.copyOf(history)))
          .as("deciding twice on the same history")
          .isEqualTo(commands);
      boolean crashed = next.hasNext() && next.next() % 100 < CRASH_CHANCE_PERCENT;
      if (crashed) {
        crashedDecisions++;
      } else {
        decisions.add(new Decision(history.size(), commands));
        commands.forEach(this::apply);
      }
      if (ending == null) {
        deliverSomething(next.hasNext() ? next.next() : 0, crashed);
      }
    }
    assertThat(ending).as("the workflow ended within " + MAX_TURNS + " turns").isNotNull();
    return this;
  }

  void assertEveryDecisionReplaysIdentically() {
    for (Decision decision : decisions) {
      assertThat(
              runtime.decide(
                  workflow, workflowId, List.copyOf(history.subList(0, decision.historySize()))))
          .as("replay of the first %d events", decision.historySize())
          .isEqualTo(decision.commands());
    }
  }

  void assertNoActivityWasScheduledTwice() {
    Set<String> seen = new HashSet<>();
    for (HistoryEntry entry : history) {
      if (entry.event() instanceof ActivityScheduled scheduled) {
        String key =
            scheduled.branch() + " " + scheduled.activity() + " " + scheduled.arguments().json();
        assertThat(seen.add(key)).as("%s scheduled once", key).isTrue();
      }
    }
  }

  List<String> scheduledActivities() {
    List<String> scheduled = new ArrayList<>();
    for (HistoryEntry entry : history) {
      if (entry.event() instanceof ActivityScheduled activity) {
        scheduled.add(activity.activity() + json.fromPayload(activity.arguments(), Object.class));
      }
    }
    return scheduled;
  }

  Command ending() {
    return ending;
  }

  boolean cancelRequested() {
    return cancelRequested;
  }

  int crashedDecisions() {
    return crashedDecisions;
  }

  List<HistoryEntry> history() {
    return List.copyOf(history);
  }

  String result() {
    return (String) json.fromPayload(((CompleteWorkflow) ending).result(), String.class);
  }

  private void apply(Command command) {
    switch (command) {
      case ScheduleActivity schedule ->
          pendingActivities.put(
              append(
                  new ActivityScheduled(
                      schedule.activity(),
                      schedule.arguments(),
                      schedule.options(),
                      schedule.branch())),
              schedule);
      case StartTimer timer ->
          pendingTimers.add(
              append(new TimerStarted(timer.duration(), timer.signal(), timer.branch())));
      case RecordVersion version ->
          append(new VersionMarker(version.changeId(), version.version()));
      case SetStatus ignored -> {}
      case CompleteWorkflow complete -> ending = complete;
      case FailWorkflow fail -> ending = fail;
      case CancelWorkflow cancel -> ending = cancel;
    }
  }

  private void deliverSomething(int choice, boolean afterACrash) {
    List<Runnable> options = new ArrayList<>();
    for (Map.Entry<Integer, ScheduleActivity> pending : pendingActivities.entrySet()) {
      options.add(() -> completeActivity(pending.getKey()));
    }
    for (int timer : pendingTimers) {
      options.add(() -> fireTimer(timer));
    }
    if (!signalsToSend.isEmpty()) {
      options.add(this::sendSignal);
    }
    if (cancelAllowed && !cancelRequested) {
      options.add(this::requestCancellation);
    }
    if (options.isEmpty()) {
      assertThat(afterACrash)
          .as("the workflow waits for nothing, after %s", history.getLast())
          .isTrue();
      return;
    }
    options.get(Math.floorMod(choice, options.size())).run();
  }

  private void completeActivity(int seq) {
    ScheduleActivity activity = pendingActivities.remove(seq);
    append(new ActivityCompleted(seq, json.toPayload(results.apply(activity))));
  }

  private void fireTimer(int seq) {
    pendingTimers.remove(seq);
    append(new TimerFired(seq));
  }

  private void sendSignal() {
    Signal signal = signalsToSend.removeFirst();
    append(new SignalReceived(signal.name(), json.toPayload(signal.payload())));
  }

  private void requestCancellation() {
    cancelRequested = true;
    append(new CancelRequested());
  }

  private int append(HistoryEvent event) {
    int seq = history.size() + 1;
    history.add(new HistoryEntry(seq, START.plusSeconds(seq), event));
    return seq;
  }
}
