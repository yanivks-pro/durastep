package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.CancelRequested;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.SignalReceived;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class History {

  static final Instant START = Instant.parse("2026-11-14T10:00:00Z");

  private static final PayloadConverter JSON = new JsonPayloadConverter();

  private final List<HistoryEntry> entries = new ArrayList<>();

  private History() {}

  static History startedWith(Object input) {
    History history = new History();
    history.append(START, new WorkflowStarted(JSON.toPayload(input)));
    return history;
  }

  History scheduled(String activity, Object... arguments) {
    return append(
        next(),
        new ActivityScheduled(activity, JSON.toArguments(arguments), ActivityOptions.DEFAULT));
  }

  History scheduledIn(String branch, String activity, Object... arguments) {
    return append(
        next(),
        new ActivityScheduled(
            activity, JSON.toArguments(arguments), ActivityOptions.DEFAULT, branch));
  }

  History completed(int scheduledSeq, Object result) {
    return append(next(), new ActivityCompleted(scheduledSeq, JSON.toPayload(result)));
  }

  History failed(int scheduledSeq, String errorType, String message) {
    return append(next(), new ActivityFailed(scheduledSeq, new Failure(errorType, message), 1));
  }

  History timerStarted(Duration duration) {
    return append(next(), new TimerStarted(duration));
  }

  History timerStartedFor(String signal, Duration timeout) {
    return append(next(), new TimerStarted(timeout, signal));
  }

  History signal(String name, Object payload) {
    return append(next(), new SignalReceived(name, JSON.toPayload(payload)));
  }

  History cancelRequested() {
    return append(next(), new CancelRequested());
  }

  History timerFired(int startedSeq) {
    return append(next(), new TimerFired(startedSeq));
  }

  History append(Instant at, HistoryEvent event) {
    entries.add(new HistoryEntry(entries.size() + 1, at, event));
    return this;
  }

  Instant timeOf(int seq) {
    return entries.get(seq - 1).recordedAt();
  }

  List<HistoryEntry> entries() {
    return List.copyOf(entries);
  }

  private Instant next() {
    return START.plusSeconds(entries.size());
  }
}
