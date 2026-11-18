package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.runtime.WorkflowTypes;
import java.util.Objects;
import java.util.Optional;

/**
 * Which workflows {@link DurastepClient#list} returns, newest first. Each method returns a new
 * query, so a query can be shared and reused.
 *
 * <pre>{@code
 * client.list(WorkflowQuery.all().state(WorkflowState.RUNNING).blocked(true).limit(20));
 * }</pre>
 */
public final class WorkflowQuery {

  /** The number of workflows returned when no limit is given. */
  public static final int DEFAULT_LIMIT = 100;

  /** The largest limit a query may have. */
  public static final int MAX_LIMIT = 1_000;

  private final WorkflowState state;
  private final String type;
  private final Boolean blocked;
  private final String idPrefix;
  private final int limit;

  private WorkflowQuery(
      WorkflowState state, String type, Boolean blocked, String idPrefix, int limit) {
    this.state = state;
    this.type = type;
    this.blocked = blocked;
    this.idPrefix = idPrefix;
    this.limit = limit;
  }

  /**
   * Returns a query for all workflows, up to {@link #DEFAULT_LIMIT}.
   *
   * @return a query without filters
   */
  public static WorkflowQuery all() {
    return new WorkflowQuery(null, null, null, null, DEFAULT_LIMIT);
  }

  /**
   * Returns a copy of this query that keeps only workflows in the given state.
   *
   * @param state the state to keep
   * @return the narrowed query
   */
  public WorkflowQuery state(WorkflowState state) {
    return new WorkflowQuery(
        Objects.requireNonNull(state, "state"), type, blocked, idPrefix, limit);
  }

  /**
   * Returns a copy of this query that keeps only workflows of the given class.
   *
   * @param workflowClass the workflow class
   * @return the narrowed query
   */
  public WorkflowQuery type(Class<? extends Workflow<?, ?>> workflowClass) {
    return type(WorkflowTypes.nameOf(Objects.requireNonNull(workflowClass, "workflowClass")));
  }

  /**
   * Returns a copy of this query that keeps only workflows with the given type name, as {@link
   * WorkflowDescription#type()} reports it.
   *
   * @param typeName the workflow type name
   * @return the narrowed query
   */
  public WorkflowQuery type(String typeName) {
    return new WorkflowQuery(
        state, Objects.requireNonNull(typeName, "typeName"), blocked, idPrefix, limit);
  }

  /**
   * Returns a copy of this query that keeps only workflows that are, or are not, blocked.
   *
   * @param blocked {@code true} for blocked workflows only, {@code false} for the others
   * @return the narrowed query
   */
  public WorkflowQuery blocked(boolean blocked) {
    return new WorkflowQuery(state, type, blocked, idPrefix, limit);
  }

  /**
   * Returns a copy of this query that keeps only workflows whose ID starts with the given text. The
   * text is matched as it is: {@code %} and {@code _} have no special meaning.
   *
   * <pre>{@code
   * client.list(WorkflowQuery.all().idStartsWith("order-"));
   * }</pre>
   *
   * @param prefix the start of the IDs to keep
   * @return the narrowed query
   * @throws IllegalArgumentException if the prefix is empty
   */
  public WorkflowQuery idStartsWith(String prefix) {
    if (Objects.requireNonNull(prefix, "prefix").isEmpty()) {
      throw new IllegalArgumentException("the ID prefix must not be empty");
    }
    return new WorkflowQuery(state, type, blocked, prefix, limit);
  }

  /**
   * Returns a copy of this query that returns at most the given number of workflows.
   *
   * @param limit from 1 to {@link #MAX_LIMIT}
   * @return the limited query
   * @throws IllegalArgumentException if the limit is out of range
   */
  public WorkflowQuery limit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "the limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
    }
    return new WorkflowQuery(state, type, blocked, idPrefix, limit);
  }

  Optional<WorkflowState> stateFilter() {
    return Optional.ofNullable(state);
  }

  Optional<String> typeFilter() {
    return Optional.ofNullable(type);
  }

  Optional<Boolean> blockedFilter() {
    return Optional.ofNullable(blocked);
  }

  Optional<String> idPrefixFilter() {
    return Optional.ofNullable(idPrefix);
  }

  int maxResults() {
    return limit;
  }
}
