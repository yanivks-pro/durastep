package io.github.yanivks_pro.durastep.examples.load;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

final class Ledger {

  private final DataSource dataSource;

  Ledger(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  void record(String effect) {
    ActivityContext activity = ActivityContext.current();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO demo.effects (idempotency_key, workflow_id, effect)"
                    + " VALUES (?, ?, ?) ON CONFLICT (idempotency_key)"
                    + " DO UPDATE SET repeats = demo.effects.repeats + 1")) {
      statement.setString(1, activity.idempotencyKey());
      statement.setString(2, activity.workflowId());
      statement.setString(3, effect);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("could not record " + effect, e);
    }
  }
}
