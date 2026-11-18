package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.payload.PayloadConverter;
import io.github.yanivks_pro.durastep.runtime.WorkflowTypes;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import io.github.yanivks_pro.durastep.store.StartResult;
import io.github.yanivks_pro.durastep.store.StoredWorkflow;
import io.github.yanivks_pro.durastep.worker.SafeWorkflowTracing;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class StoreBackedClient implements DurastepClient {

  static final Duration RESULT_POLL_INTERVAL = Duration.ofMillis(100);
  static final int MAX_REASON_LENGTH = 1_000;

  private final JdbcWorkflowStore store;
  private final PayloadConverter converter;
  private final Duration pollInterval;
  private final String operator;
  private final EngineListener listener;
  private final WorkflowTracing tracing;

  StoreBackedClient(JdbcWorkflowStore store, PayloadConverter converter, Duration pollInterval) {
    this(store, converter, pollInterval, EngineListener.NONE);
  }

  StoreBackedClient(
      JdbcWorkflowStore store,
      PayloadConverter converter,
      Duration pollInterval,
      EngineListener listener) {
    this(
        store,
        converter,
        pollInterval,
        System.getProperty("user.name", "unknown"),
        listener,
        WorkflowTracing.NONE);
  }

  private StoreBackedClient(
      JdbcWorkflowStore store,
      PayloadConverter converter,
      Duration pollInterval,
      String operator,
      EngineListener listener,
      WorkflowTracing tracing) {
    this.store = store;
    this.converter = converter;
    this.pollInterval = pollInterval;
    this.operator = operator;
    this.listener = listener;
    this.tracing = tracing;
  }

  @Override
  public EngineStats stats() {
    return store.stats();
  }

  @Override
  public Map<WorkflowState, Long> countByState() {
    return store.countByState();
  }

  @Override
  public DurastepClient actingAs(String operator) {
    Objects.requireNonNull(operator, "operator");
    if (operator.isBlank()) {
      throw new IllegalArgumentException("the operator's name must not be blank");
    }
    return new StoreBackedClient(store, converter, pollInterval, operator, listener, tracing);
  }

  @Override
  public DurastepClient withTracing(WorkflowTracing tracing) {
    Objects.requireNonNull(tracing, "tracing");
    return new StoreBackedClient(
        store, converter, pollInterval, operator, listener, SafeWorkflowTracing.around(tracing));
  }

  @Override
  public int retryAllBlocked(String reason) {
    requireReason(reason);
    int retried = 0;
    for (String workflowId : store.blockedWorkflows()) {
      if (store.retryNow(workflowId, operator, reason)) {
        retried++;
      }
    }
    return retried;
  }

  @Override
  public String rerun(String workflowId, String reason) {
    Objects.requireNonNull(workflowId, "workflowId");
    requireReason(reason);
    return store.rerun(workflowId, operator, reason);
  }

  boolean retryNow(String workflowId, String reason) {
    requireReason(reason);
    return store.retryNow(workflowId, operator, reason);
  }

  boolean terminate(String workflowId, String reason) {
    requireReason(reason);
    boolean terminated = store.terminate(workflowId, operator, reason);
    if (terminated) {
      store
          .find(workflowId)
          .ifPresent(
              workflow -> listener.workflowFinished(workflow.type(), WorkflowState.CANCELLED));
    }
    return terminated;
  }

  private static void requireReason(String reason) {
    Objects.requireNonNull(reason, "reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("a reason is required for the audit trail");
    }
    if (reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "a reason may have at most " + MAX_REASON_LENGTH + " characters");
    }
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
        store.start(
            workflowId,
            type,
            converter.toPayload(input),
            options.timeout(),
            tracing.capture(workflowId, type));
    if (!started.type().equals(type)) {
      throw new IllegalArgumentException(
          "workflow " + workflowId + " already exists as " + started.type() + ", not " + type);
    }
    if (started.created()) {
      listener.workflowStarted(type);
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
  public WorkflowHandle<Object> handle(String workflowId) {
    Objects.requireNonNull(workflowId, "workflowId");
    if (store.find(workflowId).isEmpty()) {
      throw new IllegalArgumentException("no workflow " + workflowId + " exists");
    }
    return new StoreBackedHandle<>(workflowId, Object.class, this, store, converter, pollInterval);
  }

  @Override
  public List<WorkflowDescription> list(WorkflowQuery query) {
    Objects.requireNonNull(query, "query");
    return store
        .list(
            query.stateFilter(),
            query.typeFilter(),
            query.blockedFilter(),
            query.idPrefixFilter(),
            query.maxResults())
        .stream()
        .map(StoreBackedClient::describe)
        .toList();
  }

  static WorkflowDescription describe(StoredWorkflow workflow) {
    return new WorkflowDescription(
        workflow.id(),
        workflow.type(),
        workflow.state(),
        workflow.status(),
        workflow.blocked(),
        workflow.startedAt(),
        workflow.closedAt());
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
    return new StoreBackedHandle<>(workflowId, resultType, this, store, converter, pollInterval);
  }

  private static void requireValidId(String workflowId) {
    Objects.requireNonNull(workflowId, "workflowId");
    if (workflowId.isEmpty() || workflowId.length() > 255) {
      throw new IllegalArgumentException("a workflow ID must have 1 to 255 characters");
    }
  }
}
