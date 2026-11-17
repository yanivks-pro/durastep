package io.github.yanivks_pro.durastep.spring;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.unit.DataSize;

/**
 * The {@code durastep.*} settings of a Spring Boot application.
 *
 * @param schema the database schema; default {@code durastep}
 * @param applyMigrations whether the engine creates or updates its tables on start; default true
 * @param leaseDuration how long a claimed task is reserved for this engine; default 30 seconds
 * @param workflowConcurrency how many decisions this engine runs at once; default 10
 * @param activityConcurrency how many activities this engine runs at once; default 100
 * @param activityQueues the activity queues this engine serves; default {@code default}
 * @param shutdownGrace how long shutdown waits for work in progress; default 20 seconds, within
 *     Spring's default of 30 seconds per shutdown phase
 * @param maxPayloadSize the largest piece of workflow data; default 256 KB
 * @param retention how long finished workflows are kept, such as {@code 30d}, or {@code off}
 * @param history when long histories cause a warning and a block
 * @param worker whether this application runs workers or only a client
 */
@ConfigurationProperties("durastep")
public record DurastepProperties(
    @DefaultValue("durastep") String schema,
    @DefaultValue("true") boolean applyMigrations,
    @DefaultValue("30s") Duration leaseDuration,
    @DefaultValue("10") int workflowConcurrency,
    @DefaultValue("100") int activityConcurrency,
    @DefaultValue("default") List<String> activityQueues,
    @DefaultValue("20s") Duration shutdownGrace,
    @DefaultValue("256KB") DataSize maxPayloadSize,
    @DefaultValue("30d") String retention,
    @DefaultValue History history,
    @DefaultValue Worker worker) {

  /**
   * When long histories cause a warning and a block.
   *
   * @param warnAt the number of events that logs a warning; default 10,000
   * @param limit the number of events that blocks the workflow; default 50,000
   */
  public record History(@DefaultValue("10000") int warnAt, @DefaultValue("50000") int limit) {}

  /**
   * Whether this application runs workers.
   *
   * @param enabled {@code false} to only start, signal and watch workflows that other applications
   *     run; default true
   */
  public record Worker(@DefaultValue("true") boolean enabled) {}

  /**
   * Returns how long finished workflows are kept, or empty if they are kept forever.
   *
   * @return the retention period, or empty for {@code off}
   */
  public Optional<Duration> retentionPeriod() {
    return retention.strip().equalsIgnoreCase("off")
        ? Optional.empty()
        : Optional.of(DurationStyle.detectAndParse(retention.strip()));
  }
}
