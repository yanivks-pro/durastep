package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.Payload;
import java.time.Duration;

public sealed interface HistoryEvent {

  record WorkflowStarted(Payload input) implements HistoryEvent {}

  record ActivityScheduled(String activity, Payload arguments, ActivityOptions options)
      implements HistoryEvent {}

  record ActivityCompleted(int scheduledSeq, Payload result) implements HistoryEvent {}

  record ActivityFailed(int scheduledSeq, Failure failure, int attempts) implements HistoryEvent {}

  record TimerStarted(Duration duration) implements HistoryEvent {}

  record TimerFired(int startedSeq) implements HistoryEvent {}

  record WorkflowCompleted(Payload result) implements HistoryEvent {}

  record WorkflowFailed(Failure failure) implements HistoryEvent {}
}
