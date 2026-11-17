package io.github.yanivks_pro.durastep.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.ActivityOutcome;
import io.github.yanivks_pro.durastep.api.WorkKind;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DurastepMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DurastepMetrics metrics = DurastepMetrics.bindTo(registry);

  @Test
  void eventsBecomeCountersAndTimersWithTags() {
    metrics.workflowStarted("Order");
    metrics.workflowStarted("Order");
    metrics.workflowFinished("Order", WorkflowState.COMPLETED);
    metrics.decisionMade("Order", Duration.ofMillis(12));
    metrics.activityFinished("Payments.charge", ActivityOutcome.RETRIED, Duration.ofMillis(40));
    metrics.activityFinished("Payments.charge", ActivityOutcome.SUCCEEDED, Duration.ofMillis(20));
    metrics.timerFired(Duration.ofMillis(300));
    metrics.leaseExpired(WorkKind.ACTIVITY);
    metrics.staleResultRejected(WorkKind.DECISION);

    assertThat(registry.get("durastep.workflows.started").tag("type", "Order").counter().count())
        .isEqualTo(2);
    assertThat(
            registry
                .get("durastep.workflows.finished")
                .tags("type", "Order", "state", "completed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("durastep.decisions").tag("type", "Order").timer().count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("durastep.activities")
                .tags("activity", "Payments.charge", "outcome", "retried")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(40);
    assertThat(
            registry
                .get("durastep.activities")
                .tags("activity", "Payments.charge", "outcome", "succeeded")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("durastep.timers.lateness").timer().max(TimeUnit.MILLISECONDS))
        .isEqualTo(300);
    assertThat(registry.get("durastep.leases.expired").tag("kind", "activity").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("durastep.results.rejected").tag("kind", "decision").counter().count())
        .isEqualTo(1);
  }
}
