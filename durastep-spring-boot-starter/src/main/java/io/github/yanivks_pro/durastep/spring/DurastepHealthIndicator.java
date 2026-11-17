package io.github.yanivks_pro.durastep.spring;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.EngineStats;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

final class DurastepHealthIndicator extends AbstractHealthIndicator {

  private final DurastepClient client;

  DurastepHealthIndicator(DurastepClient client) {
    super("Durastep's database cannot be reached");
    this.client = client;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) {
    EngineStats stats = client.stats();
    builder
        .up()
        .withDetail("running", stats.running())
        .withDetail("blocked", stats.blocked())
        .withDetail("waitingDecisions", stats.readyDecisions())
        .withDetail("waitingActivities", stats.readyActivities())
        .withDetail("oldestWaitingMillis", stats.oldestReadyWork().toMillis());
  }
}
