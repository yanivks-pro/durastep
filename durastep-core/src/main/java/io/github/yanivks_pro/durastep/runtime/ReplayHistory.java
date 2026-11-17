package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.CancelRequested;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.OperatorAction;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.SignalReceived;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCancelled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ReplayHistory(
    WorkflowStarted started,
    Instant startedAt,
    List<Step> steps,
    Map<String, List<Step>> stepsByBranch,
    List<HistoryEntry> signals,
    HistoryEntry cancelRequest,
    boolean deadlinePassed) {

  static final String SLEEP = "sleep";

  record Step(HistoryEntry scheduled, HistoryEntry outcome) {

    String name() {
      return nameOf(scheduled);
    }

    String branch() {
      return switch (scheduled.event()) {
        case ActivityScheduled activity -> activity.branch();
        case TimerStarted timer -> timer.branch();
        default -> throw new IllegalStateException("event " + scheduled.seq() + " is not a step");
      };
    }

    boolean hasOutcome() {
      return outcome != null;
    }
  }

  static ReplayHistory of(List<HistoryEntry> history) {
    if (history.isEmpty() || !(history.getFirst().event() instanceof WorkflowStarted started)) {
      throw new IllegalArgumentException("history must begin with WorkflowStarted");
    }
    Map<Integer, HistoryEntry> stepsBySeq = new HashMap<>();
    Map<Integer, HistoryEntry> outcomeBySeq = new HashMap<>();
    List<HistoryEntry> stepsInOrder = new ArrayList<>();
    boolean deadlinePassed = false;
    List<HistoryEntry> signals = new ArrayList<>();
    HistoryEntry cancelRequest = null;
    for (int i = 0; i < history.size(); i++) {
      HistoryEntry entry = history.get(i);
      boolean first = i == 0;
      if (entry.seq() != i + 1) {
        throw new IllegalArgumentException(
            "history must be numbered 1, 2, 3 without gaps; found "
                + entry.seq()
                + " at "
                + (i + 1));
      }
      switch (entry.event()) {
        case WorkflowStarted ignored when !first ->
            throw new IllegalArgumentException("WorkflowStarted may only be the first event");
        case WorkflowStarted ignored -> {}
        case ActivityScheduled ignored -> {
          stepsBySeq.put(entry.seq(), entry);
          stepsInOrder.add(entry);
        }
        case TimerStarted ignored -> {
          stepsBySeq.put(entry.seq(), entry);
          stepsInOrder.add(entry);
        }
        case ActivityCompleted completed ->
            recordOutcome(
                entry, completed.scheduledSeq(), ActivityScheduled.class, stepsBySeq, outcomeBySeq);
        case ActivityFailed failed ->
            recordOutcome(
                entry, failed.scheduledSeq(), ActivityScheduled.class, stepsBySeq, outcomeBySeq);
        case TimerFired fired when fired.startedSeq() == WorkflowRuntime.DEADLINE_TIMER_SEQ ->
            deadlinePassed = true;
        case TimerFired fired ->
            recordOutcome(entry, fired.startedSeq(), TimerStarted.class, stepsBySeq, outcomeBySeq);
        case SignalReceived ignored -> signals.add(entry);
        case OperatorAction ignored -> {}
        case CancelRequested ignored -> {
          if (cancelRequest == null) {
            cancelRequest = entry;
          }
        }
        case WorkflowCancelled ignored ->
            throw new IllegalStateException("the workflow has already been cancelled");
        case WorkflowCompleted ignored ->
            throw new IllegalStateException("the workflow has already completed");
        case WorkflowFailed ignored ->
            throw new IllegalStateException("the workflow has already failed");
      }
    }
    List<Step> steps =
        stepsInOrder.stream().map(step -> new Step(step, outcomeBySeq.get(step.seq()))).toList();
    Map<String, List<Step>> stepsByBranch = new LinkedHashMap<>();
    for (Step step : steps) {
      stepsByBranch.computeIfAbsent(step.branch(), branch -> new ArrayList<>()).add(step);
    }
    stepsByBranch.replaceAll((branch, branchSteps) -> List.copyOf(branchSteps));
    return new ReplayHistory(
        started,
        history.getFirst().recordedAt(),
        steps,
        Collections.unmodifiableMap(stepsByBranch),
        List.copyOf(signals),
        cancelRequest,
        deadlinePassed);
  }

  List<Step> stepsOf(String branch) {
    return stepsByBranch.getOrDefault(branch, List.of());
  }

  static String nameOf(HistoryEntry step) {
    return switch (step.event()) {
      case ActivityScheduled scheduled -> scheduled.activity();
      case TimerStarted timer when timer.signal() != null -> "awaitSignal(" + timer.signal() + ")";
      case TimerStarted ignored -> SLEEP;
      default -> throw new IllegalArgumentException("event " + step.seq() + " is not a step");
    };
  }

  private static void recordOutcome(
      HistoryEntry outcome,
      int stepSeq,
      Class<? extends HistoryEvent> expectedStep,
      Map<Integer, HistoryEntry> stepsBySeq,
      Map<Integer, HistoryEntry> outcomeBySeq) {
    HistoryEntry step = stepsBySeq.get(stepSeq);
    if (step == null || !expectedStep.isInstance(step.event())) {
      throw new IllegalArgumentException(
          "event "
              + outcome.seq()
              + " refers to step "
              + stepSeq
              + ", which was never "
              + (expectedStep == TimerStarted.class ? "started as a timer" : "scheduled"));
    }
    if (outcomeBySeq.putIfAbsent(stepSeq, outcome) != null) {
      throw new IllegalArgumentException("step " + stepSeq + " has more than one outcome");
    }
  }
}
