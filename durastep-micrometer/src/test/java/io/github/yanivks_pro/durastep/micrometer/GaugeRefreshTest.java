package io.github.yanivks_pro.durastep.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GaugeRefreshTest {

  @Test
  void theWaitingWorkGaugesNeverDisappearWhileTheyAreRefreshed() throws Exception {
    AtomicLong refreshes = new AtomicLong();
    DurastepClient client =
        (DurastepClient)
            Proxy.newProxyInstance(
                DurastepClient.class.getClassLoader(),
                new Class<?>[] {DurastepClient.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("stats")) {
                    throw new UnsupportedOperationException(method.getName());
                  }
                  long n = refreshes.incrementAndGet();
                  return new EngineStats(n, 0, n, Map.of("gpu", n), Duration.ZERO);
                });
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DurastepMetrics metrics = DurastepMetrics.bindTo(registry).watch(client, Duration.ofMillis(1));
    try {
      while (registry.find("durastep.tasks.ready").tag("queue", "gpu").gauge() == null) {
        Thread.onSpinWait();
      }
      long missing = 0;
      long readUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (System.nanoTime() < readUntil) {
        Gauge decisions =
            registry
                .find("durastep.tasks.ready")
                .tags("kind", "decision", "queue", "default")
                .gauge();
        Gauge gpu =
            registry.find("durastep.tasks.ready").tags("kind", "activity", "queue", "gpu").gauge();
        if (decisions == null || gpu == null) {
          missing++;
        }
      }

      assertThat(missing).as("reads that found no gauge").isZero();
      assertThat(refreshes.get()).as("refreshes during the test").isGreaterThan(100);
      assertThat(registry.get("durastep.tasks.ready").tag("queue", "gpu").gauge().value())
          .isPositive();
    } finally {
      metrics.close();
    }
  }
}
