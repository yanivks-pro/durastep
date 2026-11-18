package io.github.yanivks_pro.durastep.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Transactions {

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private record Notifications(String channel, Set<String> payloads) {}

  private static final Logger log = LoggerFactory.getLogger(Transactions.class);

  private final DataSource dataSource;
  private final Map<Connection, Notifications> pending = new ConcurrentHashMap<>();

  Transactions(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  <T> T inTransaction(String description, int isolation, Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(isolation);
      T result;
      try {
        result = work.run(connection);
        connection.commit();
      } catch (SQLException | RuntimeException | Error e) {
        pending.remove(connection);
        connection.rollback();
        throw e;
      }
      sendNotifications(connection);
      return result;
    } catch (SQLException e) {
      throw new StoreException("could not " + description, e);
    }
  }

  void notifyAfterCommit(Connection connection, String channel, String payload) {
    pending
        .computeIfAbsent(connection, ignored -> new Notifications(channel, new LinkedHashSet<>()))
        .payloads()
        .add(payload);
  }

  private void sendNotifications(Connection connection) {
    Notifications notifications = pending.remove(connection);
    if (notifications == null) {
      return;
    }
    try {
      connection.setAutoCommit(true);
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT pg_notify(?, payload) FROM unnest(?) AS payload")) {
        statement.setString(1, notifications.channel());
        statement.setArray(2, connection.createArrayOf("text", notifications.payloads().toArray()));
        statement.execute();
      }
    } catch (SQLException e) {
      log.debug("could not notify engines; they will find the work at their next poll", e);
    }
  }

  <T> T inTransaction(String description, Work<T> work) {
    return inTransaction(description, Connection.TRANSACTION_READ_COMMITTED, work);
  }
}
