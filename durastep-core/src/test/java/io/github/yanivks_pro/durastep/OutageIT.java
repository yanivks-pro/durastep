package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

class OutageIT {

  private static final int PROXY_PORT = 8666;

  interface Steps {
    int step(int number, long millis);
  }

  static final Map<String, AtomicInteger> runsByKey = new ConcurrentHashMap<>();

  static final class SlowSteps implements Steps {
    @Override
    public int step(int number, long millis) {
      runsByKey
          .computeIfAbsent(ActivityContext.current().idempotencyKey(), key -> new AtomicInteger())
          .incrementAndGet();
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return number;
    }
  }

  record Job(String id, long firstStepMillis) {}

  static final class ThreeSteps implements Workflow<Job, Integer> {
    @Override
    public Integer run(WorkflowContext ctx, Job job) {
      Steps steps = ctx.activities(Steps.class);
      int total = steps.step(1, job.firstStepMillis());
      ctx.sleep(Duration.ofMillis(300));
      total += steps.step(2, 50);
      return total + steps.step(3, 50);
    }
  }

  private static Network network;
  private static PostgreSQLContainer postgres;
  private static ToxiproxyContainer toxiproxy;
  private static Proxy proxy;
  private static PGSimpleDataSource throughProxy;
  private static PGSimpleDataSource direct;

  @BeforeAll
  static void startPostgresBehindAProxy() throws Exception {
    network = Network.newNetwork();
    postgres = TestPostgres.newContainer();
    postgres.withNetwork(network).withNetworkAliases("postgres").start();
    toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0").withNetwork(network);
    toxiproxy.start();
    proxy =
        new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
            .createProxy("postgres", "0.0.0.0:" + PROXY_PORT, "postgres:5432");
    throughProxy = dataSource(toxiproxy.getHost(), toxiproxy.getMappedPort(PROXY_PORT));
    direct = dataSource(postgres.getHost(), postgres.getMappedPort(5432));
  }

  @AfterAll
  static void stopContainers() {
    toxiproxy.stop();
    postgres.stop();
    network.close();
  }

  @Test
  void aTenSecondDatabaseOutageLosesNothingAndRepeatsNothing() throws Exception {
    DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(throughProxy)
            .workflow(ThreeSteps.class, ThreeSteps::new)
            .activities(Steps.class, new SlowSteps())
            .build();
    engine.start();
    List<WorkflowHandle<Integer>> handles = new ArrayList<>();
    handles.add(engine.client().start(ThreeSteps.class, "long", new Job("long", 4_000)));
    for (int i = 0; i < 20; i++) {
      handles.add(engine.client().start(ThreeSteps.class, "job-" + i, new Job("job-" + i, 100)));
    }
    await().atMost(Duration.ofSeconds(20)).until(() -> runsByKey.containsKey("long/activity-2"));

    proxy.disable();
    Thread.sleep(Duration.ofSeconds(10));
    proxy.enable();

    for (WorkflowHandle<Integer> handle : handles) {
      assertThat(handle.result(Duration.ofMinutes(1))).isEqualTo(6);
    }
    assertThat(runsByKey.get("long/activity-2").get()).isEqualTo(1);
    assertThat(runsByKey).hasSize(21 * 3);
    assertThat(runsByKey.values()).allSatisfy(runs -> assertThat(runs.get()).isEqualTo(1));
    assertThat(InvariantChecker.violations(direct)).isEmpty();
    engine.close();
  }

  private static PGSimpleDataSource dataSource(String host, int port) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + postgres.getDatabaseName());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setConnectTimeout(2);
    dataSource.setSocketTimeout(5);
    return dataSource;
  }
}
