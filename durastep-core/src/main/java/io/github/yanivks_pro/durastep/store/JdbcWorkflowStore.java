package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.activity.ActivityResult;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.Command.StartTimer;
import io.github.yanivks_pro.durastep.runtime.Failure;
import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityScheduled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerFired;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.TimerStarted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

public final class JdbcWorkflowStore {

  public static final String DEFAULT_SCHEMA = "durastep";
  public static final String DEFAULT_QUEUE = "default";

  private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

  private final Transactions transactions;
  private final CrashPoints crashPoints;
  private final EventCodec codec = new EventCodec();
  private final String workflows;
  private final String history;
  private final String tasks;
  private final String inbox;
  private final String timers;
  private final String channel;

  public JdbcWorkflowStore(DataSource dataSource) {
    this(dataSource, DEFAULT_SCHEMA);
  }

  public JdbcWorkflowStore(DataSource dataSource, String schema) {
    this(dataSource, schema, CrashPoints.NONE);
  }

  public JdbcWorkflowStore(DataSource dataSource, String schema, CrashPoints crashPoints) {
    if (!SCHEMA_NAME.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "schema must be a lower-case Postgres identifier, was " + schema);
    }
    this.transactions = new Transactions(dataSource);
    this.crashPoints = crashPoints;
    this.workflows = schema + ".workflows";
    this.history = schema + ".history_events";
    this.tasks = schema + ".tasks";
    this.inbox = schema + ".inbox";
    this.timers = schema + ".timers";
    this.channel = schema;
  }

  public StartResult start(String workflowId, String type, Payload input) {
    return start(workflowId, type, input, Optional.empty());
  }

  public StartResult start(
      String workflowId, String type, Payload input, Optional<Duration> timeout) {
    return transactions.inTransaction(
        "start workflow " + workflowId,
        connection -> {
          int inserted =
              update(
                  connection,
                  "INSERT INTO "
                      + workflows
                      + " (id, type, state, input, history_length)"
                      + " VALUES (?, ?, 'RUNNING', CAST(? AS jsonb), 1)"
                      + " ON CONFLICT (id) DO NOTHING",
                  workflowId,
                  type,
                  input.json());
          if (inserted == 0) {
            return new StartResult(false, existingType(connection, workflowId));
          }
          crashPoints.reached(CrashPoint.MID_START);
          appendEvent(connection, workflowId, 1, new WorkflowStarted(input), null);
          update(
              connection,
              "INSERT INTO " + tasks + " (workflow_id, kind) VALUES (?, 'WORKFLOW')",
              workflowId);
          notifyWork(connection, TaskKind.WORKFLOW, DEFAULT_QUEUE);
          if (timeout.isPresent()) {
            update(
                connection,
                "UPDATE "
                    + workflows
                    + " SET deadline_at = now() + ? * interval '1 millisecond' WHERE id = ?",
                timeout.get().toMillis(),
                workflowId);
            update(
                connection,
                "INSERT INTO "
                    + timers
                    + " (workflow_id, seq, fire_at) SELECT id, ?, deadline_at FROM "
                    + workflows
                    + " WHERE id = ?",
                WorkflowRuntime.DEADLINE_TIMER_SEQ,
                workflowId);
          }
          return new StartResult(true, type);
        });
  }

  public Optional<ClaimedTask> claim(TaskKind kind, String queue, String owner, Duration lease) {
    return claim(kind, Set.of(queue), owner, lease, 1).stream().findFirst();
  }

  public List<ClaimedTask> claim(
      TaskKind kind, Set<String> queues, String owner, Duration lease, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    return transactions.inTransaction(
        "claim " + kind + " tasks",
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE "
                      + tasks
                      + " SET lease_owner = ?,"
                      + " lease_until = now() + ? * interval '1 millisecond',"
                      + " fencing_token = fencing_token + 1"
                      + " WHERE id IN (SELECT id FROM "
                      + tasks
                      + " WHERE kind = ? AND queue = ANY(?) AND visible_at <= now()"
                      + " AND (lease_until IS NULL OR lease_until < now())"
                      + " ORDER BY visible_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                      + " RETURNING id, workflow_id, activity_seq, attempt, fencing_token")) {
            statement.setString(1, owner);
            statement.setLong(2, lease.toMillis());
            statement.setString(3, kind.name());
            statement.setArray(4, connection.createArrayOf("text", queues.toArray()));
            statement.setInt(5, limit);
            List<ClaimedTask> claimed = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
              while (row.next()) {
                claimed.add(
                    new ClaimedTask(
                        row.getLong("id"),
                        row.getString("workflow_id"),
                        kind,
                        row.getObject("activity_seq", Integer.class),
                        row.getInt("attempt"),
                        row.getLong("fencing_token"),
                        owner));
              }
            }
            return claimed;
          }
        });
  }

  public DecisionInput loadDecision(ClaimedTask task) {
    return transactions.inTransaction(
        "load workflow " + task.workflowId(),
        Connection.TRANSACTION_REPEATABLE_READ,
        connection -> {
          String type = existingType(connection, task.workflowId());
          List<HistoryEntry> entries = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT seq, created_at, event_type, payload::text FROM "
                      + history
                      + " WHERE workflow_id = ? ORDER BY seq")) {
            statement.setString(1, task.workflowId());
            try (ResultSet row = statement.executeQuery()) {
              while (row.next()) {
                entries.add(
                    new HistoryEntry(
                        row.getInt(1),
                        instant(row, 2),
                        codec.decode(row.getString(3), row.getString(4))));
              }
            }
          }
          List<InboxEntry> arrived = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id, received_at, kind, payload::text FROM "
                      + inbox
                      + " WHERE workflow_id = ? ORDER BY id")) {
            statement.setString(1, task.workflowId());
            try (ResultSet row = statement.executeQuery()) {
              while (row.next()) {
                arrived.add(
                    new InboxEntry(
                        row.getLong(1),
                        instant(row, 2),
                        codec.decode(row.getString(3), row.getString(4))));
              }
            }
          }
          return new DecisionInput(type, entries, arrived);
        });
  }

  public void commitDecision(ClaimedTask task, DecisionInput input, List<Command> commands) {
    transactions.inTransaction(
        "save the decision for workflow " + task.workflowId(),
        connection -> {
          boolean rerun = fence(connection, task);
          int seq = input.history().size();
          for (InboxEntry entry : input.inbox()) {
            seq++;
            appendEvent(connection, task.workflowId(), seq, entry.event(), entry.receivedAt());
            update(connection, "DELETE FROM " + inbox + " WHERE id = ?", entry.id());
          }
          boolean finished = false;
          for (Command command : commands) {
            seq++;
            switch (command) {
              case ScheduleActivity schedule -> {
                appendEvent(
                    connection,
                    task.workflowId(),
                    seq,
                    new ActivityScheduled(
                        schedule.activity(), schedule.arguments(), schedule.options()),
                    null);
                update(
                    connection,
                    "INSERT INTO "
                        + tasks
                        + " (workflow_id, kind, activity_seq, queue) VALUES (?, 'ACTIVITY', ?, ?)",
                    task.workflowId(),
                    seq,
                    schedule.options().queue());
                notifyWork(connection, TaskKind.ACTIVITY, schedule.options().queue());
              }
              case StartTimer timer -> {
                appendEvent(
                    connection, task.workflowId(), seq, new TimerStarted(timer.duration()), null);
                update(
                    connection,
                    "INSERT INTO "
                        + timers
                        + " (workflow_id, seq, fire_at)"
                        + " VALUES (?, ?, now() + ? * interval '1 millisecond')",
                    task.workflowId(),
                    seq,
                    timer.duration().toMillis());
              }
              case CompleteWorkflow complete -> {
                appendEvent(
                    connection,
                    task.workflowId(),
                    seq,
                    new WorkflowCompleted(complete.result()),
                    null);
                update(
                    connection,
                    "UPDATE "
                        + workflows
                        + " SET state = 'COMPLETED', blocked = false, blocked_error = NULL,"
                        + " result = CAST(? AS jsonb), closed_at = now()"
                        + " WHERE id = ?",
                    complete.result().json(),
                    task.workflowId());
                finished = true;
              }
              case FailWorkflow fail -> {
                appendEvent(
                    connection, task.workflowId(), seq, new WorkflowFailed(fail.failure()), null);
                update(
                    connection,
                    "UPDATE "
                        + workflows
                        + " SET state = 'FAILED', blocked = false, blocked_error = NULL,"
                        + " failure = CAST(? AS jsonb), closed_at = now()"
                        + " WHERE id = ?",
                    codec.failureJson(fail.failure()),
                    task.workflowId());
                finished = true;
              }
            }
          }
          crashPoints.reached(CrashPoint.MID_DECISION_COMMIT);
          update(
              connection,
              "UPDATE "
                  + workflows
                  + " SET history_length = ?, updated_at = now(), blocked = false, blocked_error = NULL"
                  + " WHERE id = ?",
              seq,
              task.workflowId());
          if (finished) {
            update(
                connection, "DELETE FROM " + tasks + " WHERE workflow_id = ?", task.workflowId());
            update(
                connection, "DELETE FROM " + inbox + " WHERE workflow_id = ?", task.workflowId());
            update(
                connection, "DELETE FROM " + timers + " WHERE workflow_id = ?", task.workflowId());
          } else if (rerun) {
            update(
                connection,
                "UPDATE "
                    + tasks
                    + " SET needs_rerun = false, attempt = 1, lease_owner = NULL, lease_until = NULL,"
                    + " visible_at = now() WHERE id = ?",
                task.id());
            notifyWork(connection, TaskKind.WORKFLOW, DEFAULT_QUEUE);
          } else {
            update(connection, "DELETE FROM " + tasks + " WHERE id = ?", task.id());
          }
          return null;
        });
  }

  public void blockDecision(ClaimedTask task, Throwable error, Duration retryAfter) {
    transactions.inTransaction(
        "block workflow " + task.workflowId(),
        connection -> {
          fence(connection, task);
          update(
              connection,
              "UPDATE "
                  + workflows
                  + " SET blocked = true, blocked_error = CAST(? AS jsonb), updated_at = now()"
                  + " WHERE id = ? AND state = 'RUNNING'",
              codec.errorJson(error),
              task.workflowId());
          update(
              connection,
              "UPDATE "
                  + tasks
                  + " SET attempt = attempt + 1, needs_rerun = false,"
                  + " visible_at = now() + ? * interval '1 millisecond',"
                  + " lease_owner = NULL, lease_until = NULL WHERE id = ?",
              retryAfter.toMillis(),
              task.id());
          return null;
        });
  }

  public ActivityToRun loadActivity(ClaimedTask task) {
    return transactions.inTransaction(
        "load activity " + task.activitySeq() + " of workflow " + task.workflowId(),
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT event_type, payload::text FROM "
                      + history
                      + " WHERE workflow_id = ? AND seq = ?")) {
            statement.setString(1, task.workflowId());
            statement.setInt(2, task.activitySeq());
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()
                  || !(codec.decode(row.getString(1), row.getString(2))
                      instanceof ActivityScheduled scheduled)) {
                throw new IllegalStateException(
                    "history event "
                        + task.activitySeq()
                        + " of workflow "
                        + task.workflowId()
                        + " is not a scheduled activity");
              }
              return new ActivityToRun(
                  task.workflowId(),
                  task.activitySeq(),
                  scheduled.activity(),
                  scheduled.arguments(),
                  scheduled.options(),
                  task.attempt());
            }
          }
        });
  }

  public void extendLease(ClaimedTask task, Duration lease) {
    transactions.inTransaction(
        "extend the lease of task " + task.id(),
        connection -> {
          int extended =
              update(
                  connection,
                  "UPDATE "
                      + tasks
                      + " SET lease_until = now() + ? * interval '1 millisecond'"
                      + " WHERE id = ? AND fencing_token = ? AND lease_owner = ?",
                  lease.toMillis(),
                  task.id(),
                  task.fencingToken(),
                  task.owner());
          if (extended == 0) {
            throw new StaleTaskException(task);
          }
          return null;
        });
  }

  public boolean retryActivity(
      ClaimedTask task, Failure failure, Duration delay, Optional<Duration> totalTimeout) {
    return transactions.inTransaction(
        "schedule a retry of activity " + task.activitySeq() + " of workflow " + task.workflowId(),
        connection -> {
          fence(connection, task);
          if (totalTimeout.isPresent()
              && retryWouldEndTooLate(connection, task, delay, totalTimeout.get())) {
            saveFinalOutcome(connection, task, new Failed(failure, false));
            return false;
          }
          update(
              connection,
              "UPDATE "
                  + tasks
                  + " SET attempt = attempt + 1,"
                  + " visible_at = now() + ? * interval '1 millisecond',"
                  + " last_error = CAST(? AS jsonb), lease_owner = NULL, lease_until = NULL"
                  + " WHERE id = ?",
              delay.toMillis(),
              codec.failureJson(failure),
              task.id());
          return true;
        });
  }

  public void completeActivity(ClaimedTask task, ActivityResult result) {
    transactions.inTransaction(
        "save the result of activity " + task.activitySeq() + " of workflow " + task.workflowId(),
        connection -> {
          fence(connection, task);
          saveFinalOutcome(connection, task, result);
          return null;
        });
  }

  private boolean retryWouldEndTooLate(
      Connection connection, ClaimedTask task, Duration delay, Duration totalTimeout)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT now() + ? * interval '1 millisecond'"
                + " > created_at + ? * interval '1 millisecond' FROM "
                + history
                + " WHERE workflow_id = ? AND seq = ?")) {
      statement.setLong(1, delay.toMillis());
      statement.setLong(2, totalTimeout.toMillis());
      statement.setString(3, task.workflowId());
      statement.setInt(4, task.activitySeq());
      try (ResultSet row = statement.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  private void saveFinalOutcome(Connection connection, ClaimedTask task, ActivityResult result)
      throws SQLException {
    update(connection, "DELETE FROM " + tasks + " WHERE id = ?", task.id());
    if (stateOf(connection, task.workflowId()) != WorkflowState.RUNNING) {
      return;
    }
    HistoryEvent outcome =
        switch (result) {
          case Completed completed -> new ActivityCompleted(task.activitySeq(), completed.result());
          case Failed failed ->
              new ActivityFailed(task.activitySeq(), failed.failure(), task.attempt());
        };
    update(
        connection,
        "INSERT INTO "
            + inbox
            + " (workflow_id, kind, payload, request_id) VALUES (?, ?, CAST(? AS jsonb), ?)"
            + " ON CONFLICT (workflow_id, request_id) DO NOTHING",
        task.workflowId(),
        codec.typeOf(outcome),
        codec.payloadOf(outcome),
        "activity-" + task.activitySeq());
    crashPoints.reached(CrashPoint.MID_ACTIVITY_COMPLETE);
    update(
        connection,
        "INSERT INTO "
            + tasks
            + " (workflow_id, kind) VALUES (?, 'WORKFLOW')"
            + " ON CONFLICT (workflow_id) WHERE kind = 'WORKFLOW'"
            + " DO UPDATE SET needs_rerun = true",
        task.workflowId());
    notifyWork(connection, TaskKind.WORKFLOW, DEFAULT_QUEUE);
  }

  public int fireDueTimers(int limit) {
    return transactions.inTransaction(
        "fire due timers",
        connection -> {
          List<String> workflowIds = new ArrayList<>();
          List<TimerFired> fired = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM "
                      + timers
                      + " WHERE id IN (SELECT id FROM "
                      + timers
                      + " WHERE fire_at <= now() ORDER BY fire_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                      + " RETURNING workflow_id, seq")) {
            statement.setInt(1, limit);
            try (ResultSet row = statement.executeQuery()) {
              while (row.next()) {
                workflowIds.add(row.getString(1));
                fired.add(new TimerFired(row.getInt(2)));
              }
            }
          }
          for (int i = 0; i < fired.size(); i++) {
            update(
                connection,
                "INSERT INTO "
                    + inbox
                    + " (workflow_id, kind, payload, request_id) VALUES (?, ?, CAST(? AS jsonb), ?)"
                    + " ON CONFLICT (workflow_id, request_id) DO NOTHING",
                workflowIds.get(i),
                codec.typeOf(fired.get(i)),
                codec.payloadOf(fired.get(i)),
                "timer-" + fired.get(i).startedSeq());
            update(
                connection,
                "INSERT INTO "
                    + tasks
                    + " (workflow_id, kind) VALUES (?, 'WORKFLOW')"
                    + " ON CONFLICT (workflow_id) WHERE kind = 'WORKFLOW'"
                    + " DO UPDATE SET needs_rerun = true",
                workflowIds.get(i));
            notifyWork(connection, TaskKind.WORKFLOW, DEFAULT_QUEUE);
          }
          return fired.size();
        });
  }

  public Optional<StoredWorkflow> find(String workflowId) {
    return transactions.inTransaction(
        "read workflow " + workflowId,
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT type, state, result::text, failure::text FROM "
                      + workflows
                      + " WHERE id = ?")) {
            statement.setString(1, workflowId);
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) {
                return Optional.empty();
              }
              String result = row.getString(3);
              String failure = row.getString(4);
              return Optional.of(
                  new StoredWorkflow(
                      workflowId,
                      row.getString(1),
                      WorkflowState.valueOf(row.getString(2)),
                      result == null ? null : new Payload(result),
                      failure == null ? null : codec.failure(failure)));
            }
          }
        });
  }

  private boolean fence(Connection connection, ClaimedTask task) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT fencing_token, lease_owner, needs_rerun FROM "
                + tasks
                + " WHERE id = ? FOR UPDATE")) {
      statement.setLong(1, task.id());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()
            || row.getLong(1) != task.fencingToken()
            || !task.owner().equals(row.getString(2))) {
          throw new StaleTaskException(task);
        }
        return row.getBoolean(3);
      }
    }
  }

  private void appendEvent(
      Connection connection, String workflowId, int seq, HistoryEvent event, Instant recordedAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + history
                + " (workflow_id, seq, event_type, payload, created_at)"
                + " VALUES (?, ?, ?, CAST(? AS jsonb), COALESCE(?, now()))")) {
      statement.setString(1, workflowId);
      statement.setInt(2, seq);
      statement.setString(3, codec.typeOf(event));
      statement.setString(4, codec.payloadOf(event));
      if (recordedAt == null) {
        statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        statement.setObject(5, OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC));
      }
      statement.executeUpdate();
    }
  }

  private String existingType(Connection connection, String workflowId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT type FROM " + workflows + " WHERE id = ?")) {
      statement.setString(1, workflowId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("workflow " + workflowId + " does not exist");
        }
        return row.getString(1);
      }
    }
  }

  private WorkflowState stateOf(Connection connection, String workflowId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT state FROM " + workflows + " WHERE id = ?")) {
      statement.setString(1, workflowId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("workflow " + workflowId + " does not exist");
        }
        return WorkflowState.valueOf(row.getString(1));
      }
    }
  }

  public String notificationChannel() {
    return channel;
  }

  private void notifyWork(Connection connection, TaskKind kind, String queue) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
      statement.setString(1, channel);
      statement.setString(2, kind.name() + ":" + queue);
      statement.execute();
    }
  }

  private static int update(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      return statement.executeUpdate();
    }
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
