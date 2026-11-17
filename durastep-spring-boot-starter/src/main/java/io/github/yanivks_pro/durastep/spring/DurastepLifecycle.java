package io.github.yanivks_pro.durastep.spring;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.context.SmartLifecycle;

final class DurastepLifecycle implements SmartLifecycle {

  private final Optional<DurastepEngine> engine;
  private final Optional<Consumer<DurastepClient>> watchers;
  private final DurastepClient client;
  private volatile boolean running;

  DurastepLifecycle(
      Optional<DurastepEngine> engine,
      Optional<Consumer<DurastepClient>> watchers,
      DurastepClient client) {
    this.engine = engine;
    this.watchers = watchers;
    this.client = client;
  }

  @Override
  public void start() {
    engine.ifPresent(DurastepEngine::start);
    watchers.ifPresent(watch -> watch.accept(client));
    running = true;
  }

  @Override
  public void stop(Runnable callback) {
    Thread.ofVirtual()
        .name("durastep-shutdown")
        .start(
            () -> {
              try {
                stop();
              } finally {
                callback.run();
              }
            });
  }

  @Override
  public void stop() {
    engine.ifPresent(DurastepEngine::close);
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
