package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.payload.LimitedPayloadConverter;
import io.github.yanivks_pro.durastep.store.JdbcWorkflowStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Starts workflows and finds existing ones. Obtained from {@link DurastepEngine#client()}. */
public interface DurastepClient {

  /**
   * Connects to a Durastep database without running any workers, for applications that only start,
   * signal or watch workflows while other processes run them. The schema must already exist; an
   * engine creates it on start.
   *
   * @param dataSource where the workflows are stored
   * @return a client for the {@code durastep} schema
   */
  static DurastepClient connect(DataSource dataSource) {
    return connect(dataSource, JdbcWorkflowStore.DEFAULT_SCHEMA);
  }

  /**
   * Connects to a Durastep database in the given schema without running any workers. See {@link
   * #connect(DataSource)}.
   *
   * @param dataSource where the workflows are stored
   * @param schema the schema the engines use
   * @return a client for that schema
   * @throws IllegalArgumentException if the schema name is not a plain identifier
   */
  static DurastepClient connect(DataSource dataSource, String schema) {
    Objects.requireNonNull(dataSource, "dataSource");
    return new StoreBackedClient(
        new JdbcWorkflowStore(dataSource, schema),
        DurastepEngine.converterFor(LimitedPayloadConverter.DEFAULT_LIMIT, Optional.empty()),
        StoreBackedClient.RESULT_POLL_INTERVAL);
  }

  /**
   * Connects to a Durastep database whose workflow data is encoded, for example encrypted, without
   * running any workers. See {@link #connect(DataSource)}.
   *
   * @param dataSource where the workflows are stored
   * @param schema the schema the engines use
   * @param payloadCodec the codec the engines use
   * @return a client for that schema
   */
  static DurastepClient connect(DataSource dataSource, String schema, PayloadCodec payloadCodec) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(payloadCodec, "payloadCodec");
    return new StoreBackedClient(
        new JdbcWorkflowStore(dataSource, schema),
        DurastepEngine.converterFor(
            LimitedPayloadConverter.DEFAULT_LIMIT, Optional.of(payloadCodec)),
        StoreBackedClient.RESULT_POLL_INTERVAL);
  }

  /**
   * Starts a workflow with a business ID chosen by the caller.
   *
   * <p>Starting is safe to repeat: if a workflow with this ID already exists, nothing new is
   * started and a handle to the existing workflow is returned.
   *
   * @param workflowClass the workflow to start
   * @param workflowId the business ID, for example {@code order-42}; 1 to 255 characters
   * @param input the workflow's input
   * @param <I> the workflow's input type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if a workflow with this ID exists with a different type
   */
  <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass, String workflowId, I input);

  /**
   * Starts a workflow with options, such as a deadline for the whole workflow. Like {@link
   * #start(Class, String, Object)}, it is safe to repeat; the options of an existing workflow are
   * not changed.
   *
   * @param workflowClass the workflow to start
   * @param workflowId the business ID, for example {@code order-42}; 1 to 255 characters
   * @param input the workflow's input
   * @param options the options for this workflow
   * @param <I> the workflow's input type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if a workflow with this ID exists with a different type
   */
  <I, O> WorkflowHandle<O> start(
      Class<? extends Workflow<I, O>> workflowClass,
      String workflowId,
      I input,
      WorkflowOptions options);

  /**
   * Sends a signal to a running workflow, which receives it with {@link
   * WorkflowContext#awaitSignal}. Each call sends a new signal; to make retries of the same call
   * safe, use {@link #signal(String, String, Object, String)} with a request ID.
   *
   * @param workflowId the workflow's business ID
   * @param name the signal's name
   * @param payload the signal's payload
   * @throws IllegalArgumentException if no workflow with this ID exists
   * @throws IllegalStateException if the workflow has already finished
   */
  void signal(String workflowId, String name, Object payload);

  /**
   * Sends a signal once per request ID: repeating the call with the same request ID, for example
   * after a network error, does not deliver the signal a second time.
   *
   * @param workflowId the workflow's business ID
   * @param name the signal's name
   * @param payload the signal's payload
   * @param requestId an ID chosen by the caller, unique for each distinct signal
   * @return {@code true} if the signal was accepted now, {@code false} if this request ID was
   *     already used
   * @throws IllegalArgumentException if no workflow with this ID exists
   * @throws IllegalStateException if the workflow has already finished
   */
  boolean signal(String workflowId, String name, Object payload, String requestId);

  /**
   * Asks a running workflow to stop. The workflow sees a {@link CancelledException} at its next
   * wait point, may clean up, and then ends in {@link WorkflowState#CANCELLED}. Activities that are
   * already running are not interrupted, but their results are ignored.
   *
   * @param workflowId the workflow's business ID
   * @return {@code true} if the request was accepted now, {@code false} if the workflow had already
   *     finished or been asked to cancel
   * @throws IllegalArgumentException if no workflow with this ID exists
   */
  boolean cancel(String workflowId);

  /**
   * Returns a handle to an existing workflow without knowing its class, for tools such as the
   * command-line interface. Its {@link WorkflowHandle#result} returns plain JSON values: maps,
   * lists, strings, numbers, booleans or {@code null}.
   *
   * @param workflowId the workflow's business ID
   * @return a handle to the workflow
   * @throws IllegalArgumentException if no workflow with this ID exists
   */
  WorkflowHandle<Object> handle(String workflowId);

  /**
   * Returns the workflows that match a query, newest first.
   *
   * @param query which workflows to return and how many
   * @return a snapshot of each matching workflow
   */
  List<WorkflowDescription> list(WorkflowQuery query);

  /**
   * Counts all workflows and pending work in the database: how many workflows run and how many are
   * blocked, how much work waits for an engine, and how long the oldest has waited.
   *
   * @return a snapshot of the counts
   */
  EngineStats stats();

  /**
   * Counts the workflows in the database by state, including finished ones that retention has not
   * deleted yet.
   *
   * <pre>{@code
   * long failed = client.countByState().get(WorkflowState.FAILED);
   * }</pre>
   *
   * @return a count for every state, zero when none are in it
   */
  Map<WorkflowState, Long> countByState();

  /**
   * Retries every blocked workflow now. See {@link WorkflowHandle#retryNow(String)}.
   *
   * @param reason why, for the audit trail of each workflow
   * @return how many workflows will be retried
   * @throws IllegalArgumentException if the reason is blank
   */
  int retryAllBlocked(String reason);

  /**
   * Starts a new workflow with the same type and input as a finished one, for example to process an
   * order again after fixing data. The new workflow's ID is the original's with {@code -rerun-1},
   * {@code -rerun-2} and so on appended; it is linked to the original, which stays as it is, and
   * its history records who started it and why. The original's deadline is not copied.
   *
   * @param workflowId the finished workflow to run again
   * @param reason why, for the audit trail
   * @return the ID of the new workflow
   * @throws IllegalArgumentException if no such workflow exists or the reason is blank
   * @throws IllegalStateException if the workflow is still running
   */
  String rerun(String workflowId, String reason);

  /**
   * Returns a client that records the given name as the operator in the audit trail of {@link
   * WorkflowHandle#retryNow}, {@link WorkflowHandle#terminate}, {@link #retryAllBlocked} and {@link
   * #rerun}. By default the name is that of the operating system user.
   *
   * @param operator who is acting, such as a user name
   * @return a client acting as that operator
   */
  DurastepClient actingAs(String operator);

  /**
   * Returns a client that captures the caller's trace context when it starts a workflow, so that
   * the workflow's work joins the caller's trace. An engine's own client uses the engine's tracing.
   *
   * <pre>{@code
   * DurastepClient client = DurastepClient.connect(dataSource).withTracing(tracing);
   * }</pre>
   *
   * @param tracing the tracing to capture with, or {@link WorkflowTracing#NONE}
   * @return a client that captures trace context
   */
  DurastepClient withTracing(WorkflowTracing tracing);

  /**
   * Returns a handle to an existing workflow.
   *
   * @param workflowId the workflow's business ID
   * @param workflowClass the workflow's class, which determines the result type
   * @param <O> the workflow's result type
   * @return a handle to the workflow
   * @throws IllegalArgumentException if no workflow with this ID and type exists
   */
  <O> WorkflowHandle<O> handle(String workflowId, Class<? extends Workflow<?, O>> workflowClass);
}
