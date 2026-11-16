package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.WorkflowTypes;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StartResult;
import io.github.yanivks_pro.durastep.store.StoredWorkflow;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

final class StoreBackedClient implements DurastepClient {

  private final JdbcWorkflowStore store;
  private final PayloadConverter converter;
  private final Duration pollInterval;

  StoreBackedClient(JdbcWorkflowStore store, PayloadConverter converter, Duration pollInterval) {
    this.store = store;
    this.converter = converter;
    this.pollInterval = pollInterval;
  }

  @Override
  public <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass, String workflowId, I input) {
    return start(workflowClass, workflowId, input, WorkflowOptions.DEFAULT);
  }

  @Override
  public <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass,
      String workflowId,
      I input,
      WorkflowOptions options) {
    Objects.requireNonNull(workflowClass, "workflowClass");
    Objects.requireNonNull(options, "options");
    requireValidId(workflowId);
    String type = WorkflowTypes.nameOf(workflowClass);
    StartResult started =
        store.start(workflowId, type, converter.toPayload(input), options.timeout());
    if (!started.type().equals(type)) {
      throw new IllegalArgumentException(
          "workflow " + workflowId + " already exists as " + started.type() + ", not " + type);
    }
    return newHandle(workflowId, workflowClass);
  }

  @Override
  public void signal(String workflowId, String name, Object payload) {
    signal(workflowId, name, payload, UUID.randomUUID().toString());
  }

  @Override
  public boolean signal(String workflowId, String name, Object payload, String requestId) {
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(requestId, "requestId");
    if (store.find(workflowId).isEmpty()) {
      throw new IllegalArgumentException("no workflow " + workflowId + " exists");
    }
    return store.signal(workflowId, name, converter.toPayload(payload), requestId);
  }

  @Override
  public boolean cancel(String workflowId) {
    Objects.requireNonNull(workflowId, "workflowId");
    if (store.find(workflowId).isEmpty()) {
      throw new IllegalArgumentException("no workflow " + workflowId + " exists");
    }
    return store.cancel(workflowId);
  }

  @Override
  public <O> WorkflowHandle<O> handle(
      String workflowId, Class<? extends Workflow<?, O>> workflowClass) {
    Objects.requireNonNull(workflowClass, "workflowClass");
    String type = WorkflowTypes.nameOf(workflowClass);
    StoredWorkflow workflow =
        store
            .find(workflowId)
            .orElseThrow(
                () -> new IllegalArgumentException("no workflow " + workflowId + " exists"));
    if (!workflow.type().equals(type)) {
      throw new IllegalArgumentException(
          "workflow " + workflowId + " is a " + workflow.type() + ", not " + type);
    }
    return newHandle(workflowId, workflowClass);
  }

  private <O> WorkflowHandle<O> newHandle(String workflowId, Class<?> workflowClass) {
    Type resultType = WorkflowTypes.outputOf(workflowClass);
    return new StoreBackedHandle<>(workflowId, resultType, store, converter, pollInterval);
  }

  private static void requireValidId(String workflowId) {
    Objects.requireNonNull(workflowId, "workflowId");
    if (workflowId.isEmpty() || workflowId.length() > 255) {
      throw new IllegalArgumentException("a workflow ID must have 1 to 255 characters");
    }
  }
}
