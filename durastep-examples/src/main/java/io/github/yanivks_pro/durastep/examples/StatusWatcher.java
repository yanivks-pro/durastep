package io.github.yanivks_pro.durastep.examples;

import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import java.time.Duration;
import java.util.Objects;

public final class StatusWatcher {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  private final WorkflowHandle<?> workflow;
  private final Narrator narrator;
  private final Thread thread;
  private volatile boolean watching = true;
  private String lastStatus;

  private StatusWatcher(WorkflowHandle<?> workflow, Narrator narrator) {
    this.workflow = workflow;
    this.narrator = narrator;
    this.thread = Thread.ofVirtual().name("status-watcher").start(this::watch);
  }

  public static StatusWatcher start(WorkflowHandle<?> workflow, Narrator narrator) {
    return new StatusWatcher(workflow, narrator);
  }

  public static void awaitStatus(WorkflowHandle<?> workflow, String status, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!status.equals(workflow.describe().status())) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "workflow " + workflow.id() + " did not reach status '" + status + "'");
      }
      Thread.sleep(POLL_INTERVAL);
    }
  }

  public void stop() throws InterruptedException {
    watching = false;
    thread.join();
    report();
  }

  private void watch() {
    while (watching) {
      report();
      try {
        Thread.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private synchronized void report() {
    String status = workflow.describe().status();
    if (status != null && !Objects.equals(status, lastStatus)) {
      lastStatus = status;
      narrator.say("status", status);
    }
  }
}
