package io.github.yanivks_pro.durastep.benchmarks;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;

final class Measurements {

  private static final String STEP_CYCLES =
      """
      WITH steps AS (
        SELECT h.workflow_id, h.created_at,
               COALESCE(lead(h.created_at) OVER (PARTITION BY h.workflow_id ORDER BY h.seq),
                        w.closed_at) AS next_at
        FROM durastep.history_events h
        JOIN durastep.workflows w ON w.id = h.workflow_id
        WHERE h.event_type = 'ACTIVITY_SCHEDULED' AND starts_with(h.workflow_id, ?))
      SELECT EXTRACT(EPOCH FROM next_at - created_at) * 1000 FROM steps WHERE next_at IS NOT NULL
      """;

  private static final String ACTIVITY_HOPS =
      """
      SELECT EXTRACT(EPOCH FROM done.created_at - scheduled.created_at) * 1000
      FROM durastep.history_events scheduled
      JOIN durastep.history_events done
        ON done.workflow_id = scheduled.workflow_id
       AND done.event_type = 'ACTIVITY_COMPLETED'
       AND (done.payload ->> 'scheduledSeq')::int = scheduled.seq
      WHERE scheduled.event_type = 'ACTIVITY_SCHEDULED' AND starts_with(scheduled.workflow_id, ?)
      """;

  private static final String WORKFLOWS =
      """
      SELECT EXTRACT(EPOCH FROM closed_at - created_at) * 1000
      FROM durastep.workflows
      WHERE state = 'COMPLETED' AND starts_with(id, ?)
      """;

  private static final String TIMER_LATENESS =
      """
      SELECT EXTRACT(EPOCH FROM fired.created_at
                     - (started.created_at + (started.payload ->> 'duration')::interval)) * 1000
      FROM durastep.history_events started
      JOIN durastep.history_events fired
        ON fired.workflow_id = started.workflow_id
       AND fired.event_type = 'TIMER_FIRED'
       AND (fired.payload ->> 'startedSeq')::int = started.seq
      WHERE started.event_type = 'TIMER_STARTED' AND starts_with(started.workflow_id, ?)
      """;

  private final DataSource dataSource;

  Measurements(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  Percentiles stepCycles(String prefix) throws SQLException {
    return percentiles(STEP_CYCLES, prefix);
  }

  Percentiles activityHops(String prefix) throws SQLException {
    return percentiles(ACTIVITY_HOPS, prefix);
  }

  Percentiles workflows(String prefix) throws SQLException {
    return percentiles(WORKFLOWS, prefix);
  }

  Percentiles timerLateness(String prefix) throws SQLException {
    return percentiles(TIMER_LATENESS, prefix);
  }

  Instant now() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT clock_timestamp()")) {
      row.next();
      return row.getTimestamp(1).toInstant();
    }
  }

  Instant lastClosed(String prefix) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT max(closed_at) FROM durastep.workflows WHERE starts_with(id, ?)")) {
      statement.setString(1, prefix);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getTimestamp(1).toInstant();
      }
    }
  }

  long completed(String prefix) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM durastep.workflows"
                    + " WHERE state = 'COMPLETED' AND starts_with(id, ?)")) {
      statement.setString(1, prefix);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  void forgetEverything() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE durastep.workflows CASCADE");
      statement.execute("VACUUM ANALYZE");
    }
  }

  String postgresVersion() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SHOW server_version")) {
      row.next();
      return row.getString(1);
    }
  }

  private Percentiles percentiles(String values, String prefix) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*), percentile_cont(ARRAY[0.5, 0.9, 0.99]) WITHIN GROUP (ORDER BY"
                    + " ms), max(ms) FROM ("
                    + values
                    + ") AS measured(ms)")) {
      statement.setString(1, prefix);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        long count = row.getLong(1);
        if (count == 0) {
          return new Percentiles(0, 0, 0, 0, 0);
        }
        Array array = row.getArray(2);
        Double[] values3 = (Double[]) array.getArray();
        return new Percentiles(count, values3[0], values3[1], values3[2], row.getDouble(3));
      }
    }
  }
}
