package io.github.yanivks_pro.durastep.benchmarks;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.InstantSteps;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.Sleeper;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.Steps;
import io.github.yanivks_pro.durastep.benchmarks.Workloads.ThreeSteps;
import javax.sql.DataSource;

record EngineSettings(int workflowConcurrency, int activityConcurrency) {

  static final EngineSettings DEFAULTS = new EngineSettings(10, 100);

  DurastepEngine engine(DataSource dataSource) {
    return DurastepEngine.builder()
        .dataSource(dataSource)
        .workflowConcurrency(workflowConcurrency)
        .activityConcurrency(activityConcurrency)
        .noRetention()
        .workflow(ThreeSteps.class, ThreeSteps::new)
        .workflow(Sleeper.class, Sleeper::new)
        .activities(Steps.class, new InstantSteps())
        .build();
  }
}
