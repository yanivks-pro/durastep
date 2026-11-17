package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.Payload;
import java.time.Duration;

public sealed interface HistoryEvent {

  String MAIN_BRANCH = "0";

  record WorkflowStarted(Payload input) implements HistoryEvent {}

  record ActivityScheduled(
      String activity, Payload arguments, ActivityOptions options, String branch)
      implements HistoryEvent {

    public ActivityScheduled(String activity, Payload arguments, ActivityOptions options) {
      this(activity, arguments, options, MAIN_BRANCH);
    }
  }

  record ActivityCompleted(int scheduledSeq, Payload result) implements HistoryEvent {}

  record ActivityFailed(int scheduledSeq, Failure failure, int attempts) implements HistoryEvent {}

  record TimerStarted(Duration duration, String signal, String branch) implements HistoryEvent {

    public TimerStarted(Duration duration, String signal) {
      this(duration, signal, MAIN_BRANCH);
    }

    public TimerStarted(Duration duration) {
      this(duration, null);
    }
  }

  record TimerFired(int startedSeq) implements HistoryEvent {}

  record SignalReceived(String name, Payload payload) implements HistoryEvent {}

  record CancelRequested() implements HistoryEvent {}

  record OperatorAction(String action, String by, String reason) implements HistoryEvent {}

  record VersionMarker(String changeId, int version) implements HistoryEvent {}

  record WorkflowCompleted(Payload result) implements HistoryEvent {}

  record WorkflowCancelled() implements HistoryEvent {}

  record WorkflowFailed(Failure failure) implements HistoryEvent {}
}
