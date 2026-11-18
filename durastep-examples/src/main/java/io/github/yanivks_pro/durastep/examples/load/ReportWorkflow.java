package io.github.yanivks_pro.durastep.examples.load;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Duration;
import java.util.function.Consumer;

public final class ReportWorkflow implements Workflow<String, String> {

  public interface Reports {
    String render(String reportId);
  }

  static final ActivityOptions HEARTBEATS =
      ActivityOptions.builder().heartbeatTimeout(Duration.ofSeconds(2)).build();

  @Override
  public String run(WorkflowContext ctx, String reportId) {
    ctx.setStatus("rendering");
    String file = ctx.activities(Reports.class, HEARTBEATS).render(reportId);
    ctx.setStatus("rendered");
    return file;
  }

  static final class SlowReports implements Reports {
    private final Consumer<String> effects;

    SlowReports(Consumer<String> effects) {
      this.effects = effects;
    }

    @Override
    public String render(String reportId) {
      for (int part = 0; part < 6; part++) {
        try {
          Thread.sleep(Duration.ofMillis(500));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while rendering " + reportId, e);
        }
        ActivityContext.current().heartbeat();
      }
      effects.accept("render report");
      return reportId + ".pdf";
    }
  }
}
