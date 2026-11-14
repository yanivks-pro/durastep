package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record ReplayHistory(WorkflowStarted started, Instant startedAt, List<Step> steps) {

  record Step(HistoryEntry scheduled, HistoryEntry outcome) {

    String activity() {
      return ((ActivityScheduled) scheduled.event()).activity();
    }

    boolean hasOutcome() {
      return outcome != null;
    }
  }

  static ReplayHistory of(List<HistoryEntry> history) {
    if (history.isEmpty() || !(history.getFirst().event() instanceof WorkflowStarted started)) {
      throw new IllegalArgumentException("history must begin with WorkflowStarted");
    }
    Map<Integer, HistoryEntry> scheduledBySeq = new HashMap<>();
    Map<Integer, HistoryEntry> outcomeBySeq = new HashMap<>();
    List<HistoryEntry> scheduledInOrder = new ArrayList<>();
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
          scheduledBySeq.put(entry.seq(), entry);
          scheduledInOrder.add(entry);
        }
        case ActivityCompleted completed ->
            recordOutcome(entry, completed.scheduledSeq(), scheduledBySeq, outcomeBySeq);
        case ActivityFailed failed ->
            recordOutcome(entry, failed.scheduledSeq(), scheduledBySeq, outcomeBySeq);
        case WorkflowCompleted ignored ->
            throw new IllegalStateException("the workflow has already completed");
        case WorkflowFailed ignored ->
            throw new IllegalStateException("the workflow has already failed");
      }
    }
    List<Step> steps =
        scheduledInOrder.stream()
            .map(scheduled -> new Step(scheduled, outcomeBySeq.get(scheduled.seq())))
            .toList();
    return new ReplayHistory(started, history.getFirst().recordedAt(), steps);
  }

  private static void recordOutcome(
      HistoryEntry outcome,
      int scheduledSeq,
      Map<Integer, HistoryEntry> scheduledBySeq,
      Map<Integer, HistoryEntry> outcomeBySeq) {
    if (!scheduledBySeq.containsKey(scheduledSeq)) {
      throw new IllegalArgumentException(
          "event "
              + outcome.seq()
              + " refers to activity "
              + scheduledSeq
              + ", which was never scheduled");
    }
    if (outcomeBySeq.putIfAbsent(scheduledSeq, outcome) != null) {
      throw new IllegalArgumentException("activity " + scheduledSeq + " has more than one outcome");
    }
  }
}
