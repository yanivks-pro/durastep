package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StoredWorkflow;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

final class StoreBackedHandle<O> implements WorkflowHandle<O> {

  private static final Duration ASYNC_WAIT_SLICE = Duration.ofSeconds(1);

  private final String workflowId;
  private final Type resultType;
  private final StoreBackedClient client;
  private final JdbcWorkflowStore store;
  private final PayloadConverter converter;
  private final Duration pollInterval;

  StoreBackedHandle(
      String workflowId,
      Type resultType,
      StoreBackedClient client,
      JdbcWorkflowStore store,
      PayloadConverter converter,
      Duration pollInterval) {
    this.workflowId = workflowId;
    this.resultType = resultType;
    this.client = client;
    this.store = store;
    this.converter = converter;
    this.pollInterval = pollInterval;
  }

  @Override
  public String id() {
    return workflowId;
  }

  @Override
  public WorkflowDescription describe() {
    StoredWorkflow workflow =
        store
            .find(workflowId)
            .orElseThrow(() -> new IllegalStateException("workflow " + workflowId + " is gone"));
    return StoreBackedClient.describe(workflow);
  }

  @Override
  public CompletableFuture<O> resultAsync() {
    CompletableFuture<O> future = new CompletableFuture<>();
    Thread.ofVirtual()
        .name("durastep-result-" + workflowId)
        .start(
            () -> {
              while (!future.isDone()) {
                try {
                  future.complete(result(ASYNC_WAIT_SLICE));
                } catch (TimeoutException stillRunning) {
                  if (future.isCancelled()) {
                    return;
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  future.completeExceptionally(e);
                } catch (RuntimeException e) {
                  future.completeExceptionally(e);
                }
              }
            });
    return future;
  }

  @Override
  public void signal(String name, Object payload) {
    client.signal(workflowId, name, payload);
  }

  @Override
  public boolean signal(String name, Object payload, String requestId) {
    return client.signal(workflowId, name, payload, requestId);
  }

  @Override
  public boolean cancel() {
    return client.cancel(workflowId);
  }

  @Override
  public boolean retryNow(String reason) {
    return client.retryNow(workflowId, reason);
  }

  @Override
  public boolean terminate(String reason) {
    return client.terminate(workflowId, reason);
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
            throw new WorkflowCancelledException(
                workflowId,
                workflow.failure() != null
                        && workflow.failure().type().equals(JdbcWorkflowStore.TERMINATED)
                    ? workflow.failure().message()
                    : null);
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
