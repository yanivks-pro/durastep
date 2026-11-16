package io.github.yanivks_pro.durastep;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class InvariantChecker {

  private static final Map<String, String> RULES = new LinkedHashMap<>();

  static {
    RULES.put(
        "history is numbered 1..n without gaps",
        "SELECT workflow_id FROM durastep.history_events"
            + " GROUP BY workflow_id HAVING min(seq) <> 1 OR max(seq) <> count(*)");
    RULES.put(
        "history_length matches the history",
        "SELECT w.id FROM durastep.workflows w"
            + " WHERE w.history_length <>"
            + " (SELECT count(*) FROM durastep.history_events h WHERE h.workflow_id = w.id)");
    RULES.put(
        "every workflow starts with WORKFLOW_STARTED",
        "SELECT w.id FROM durastep.workflows w WHERE NOT EXISTS (SELECT 1 FROM"
            + " durastep.history_events h WHERE h.workflow_id = w.id AND h.seq = 1"
            + " AND h.event_type = 'WORKFLOW_STARTED')");
    RULES.put(
        "an activity has at most one result",
        "SELECT workflow_id || '/' || (payload->>'scheduledSeq') FROM durastep.history_events"
            + " WHERE event_type IN ('ACTIVITY_COMPLETED', 'ACTIVITY_FAILED')"
            + " GROUP BY workflow_id, payload->>'scheduledSeq' HAVING count(*) > 1");
    RULES.put(
        "every result belongs to a scheduled activity",
        "SELECT r.workflow_id || '/' || r.seq FROM durastep.history_events r"
            + " WHERE r.event_type IN ('ACTIVITY_COMPLETED', 'ACTIVITY_FAILED') AND NOT EXISTS"
            + " (SELECT 1 FROM durastep.history_events s WHERE s.workflow_id = r.workflow_id"
            + " AND s.seq = (r.payload->>'scheduledSeq')::int"
            + " AND s.event_type = 'ACTIVITY_SCHEDULED')");
    RULES.put(
        "a finished workflow has no tasks",
        "SELECT DISTINCT w.id FROM durastep.workflows w JOIN durastep.tasks t"
            + " ON t.workflow_id = w.id WHERE w.state <> 'RUNNING'");
    RULES.put(
        "a finished workflow has no inbox entries",
        "SELECT DISTINCT w.id FROM durastep.workflows w JOIN durastep.inbox i"
            + " ON i.workflow_id = w.id WHERE w.state <> 'RUNNING'");
    RULES.put(
        "a finished workflow has no timers",
        "SELECT DISTINCT w.id FROM durastep.workflows w JOIN durastep.timers t"
            + " ON t.workflow_id = w.id WHERE w.state <> 'RUNNING'");
    RULES.put(
        "a timer fires at most once and only for a started timer",
        "SELECT f.workflow_id || '/' || (f.payload->>'startedSeq') FROM durastep.history_events f"
            + " WHERE f.event_type = 'TIMER_FIRED' AND (f.payload->>'startedSeq')::int <> 0"
            + " GROUP BY f.workflow_id, f.payload->>'startedSeq'"
            + " HAVING count(*) > 1 OR NOT bool_and(EXISTS (SELECT 1 FROM durastep.history_events s"
            + " WHERE s.workflow_id = f.workflow_id AND s.seq = (f.payload->>'startedSeq')::int"
            + " AND s.event_type = 'TIMER_STARTED'))");
    RULES.put(
        "the deadline timer fires only for a workflow with a deadline",
        "SELECT f.workflow_id FROM durastep.history_events f JOIN durastep.workflows w"
            + " ON w.id = f.workflow_id WHERE f.event_type = 'TIMER_FIRED'"
            + " AND (f.payload->>'startedSeq')::int = 0 AND w.deadline_at IS NULL");
    RULES.put(
        "a finished workflow ends with its final event",
        "SELECT w.id FROM durastep.workflows w WHERE w.state <> 'RUNNING' AND NOT EXISTS"
            + " (SELECT 1 FROM durastep.history_events h WHERE h.workflow_id = w.id"
            + " AND h.seq = w.history_length"
            + " AND h.event_type IN ('WORKFLOW_COMPLETED', 'WORKFLOW_FAILED', 'WORKFLOW_CANCELLED'))");
  }

  private InvariantChecker() {}

  public static List<String> violations(DataSource dataSource) throws SQLException {
    List<String> violations = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (Map.Entry<String, String> rule : RULES.entrySet()) {
        try (ResultSet row = statement.executeQuery(rule.getValue())) {
          while (row.next()) {
            violations.add(rule.getKey() + ": " + row.getString(1));
          }
        }
      }
    }
    return violations;
  }
}
