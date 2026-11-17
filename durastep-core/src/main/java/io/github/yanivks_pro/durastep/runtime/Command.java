package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.payload.Payload;
import java.time.Duration;

public sealed interface Command {

  record ScheduleActivity(
      String activity, Payload arguments, ActivityOptions options, String branch)
      implements Command {

    public ScheduleActivity(String activity, Payload arguments, ActivityOptions options) {
      this(activity, arguments, options, HistoryEvent.MAIN_BRANCH);
    }
  }

  record StartTimer(Duration duration, String signal, String branch) implements Command {

    public StartTimer(Duration duration, String signal) {
      this(duration, signal, HistoryEvent.MAIN_BRANCH);
    }

    public StartTimer(Duration duration) {
      this(duration, null);
    }
  }

  record SetStatus(String status) implements Command {}

  record CompleteWorkflow(Payload result) implements Command {}

  record FailWorkflow(Failure failure) implements Command {}

  record CancelWorkflow() implements Command {}
}
