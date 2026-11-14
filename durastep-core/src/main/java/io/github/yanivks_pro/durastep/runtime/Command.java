package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.payload.Payload;

public sealed interface Command {

  record ScheduleActivity(String activity, Payload arguments) implements Command {}

  record CompleteWorkflow(Payload result) implements Command {}

  record FailWorkflow(Failure failure) implements Command {}
}
