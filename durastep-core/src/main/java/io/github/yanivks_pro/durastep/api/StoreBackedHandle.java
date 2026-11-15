package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StoredWorkflow;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

final class StoreBackedHandle<O> implements WorkflowHandle<O> {

  private final String workflowId;
  private final Type resultType;
  private final JdbcWorkflowStore store;
  private final PayloadConverter converter;
  private final Duration pollInterval;

  StoreBackedHandle(
      String workflowId,
      Type resultType,
      JdbcWorkflowStore store,
      PayloadConverter converter,
      Duration pollInterval) {
    this.workflowId = workflowId;
    this.resultType = resultType;
    this.store = store;
    this.converter = converter;
    this.pollInterval = pollInterval;
  }

  @Override
  public String id() {
    return workflowId;
  }

  @Override
  public O result(Duration timeout) throws TimeoutException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      StoredWorkflow workflow =
          store
              .find(workflowId)
              .orElseThrow(() -> new IllegalStateException("workflow " + workflowId + " is gone"));
      switch (workflow.state()) {
        case COMPLETED -> {
          @SuppressWarnings("unchecked")
          O result = (O) converter.fromPayload(workflow.result(), resultType);
          return result;
        }
        case FAILED ->
            throw new WorkflowFailedException(
                workflowId, workflow.failure().type(), workflow.failure().message());
        case CANCELLED ->
            throw new IllegalStateException("workflow " + workflowId + " was cancelled");
        case RUNNING -> {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new TimeoutException(
                "workflow " + workflowId + " did not finish within " + timeout);
          }
          Thread.sleep(Duration.ofNanos(Math.min(remaining, pollInterval.toNanos())));
        }
      }
    }
  }
}
