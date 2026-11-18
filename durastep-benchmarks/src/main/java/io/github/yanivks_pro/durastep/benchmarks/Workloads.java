package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Duration;

final class Workloads {

  private Workloads() {}

  interface Steps {
    String reserve(String order);

    String charge(String order);

    String ship(String order);
  }

  static final class InstantSteps implements Steps {
    @Override
    public String reserve(String order) {
      return "r";
    }

    @Override
    public String charge(String order) {
      return "c";
    }

    @Override
    public String ship(String order) {
      return "s";
    }
  }

  static final class ThreeSteps implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      Steps steps = ctx.activities(Steps.class);
      return steps.reserve(order) + steps.charge(order) + steps.ship(order);
    }
  }

  static final class Sleeper implements Workflow<Long, Long> {
    @Override
    public Long run(WorkflowContext ctx, Long millis) {
      ctx.sleep(Duration.ofMillis(millis));
      return millis;
    }
  }
}
