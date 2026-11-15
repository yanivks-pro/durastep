package io.github.yanivks_pro.durastep.store;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

final class Transactions {

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private final DataSource dataSource;

  Transactions(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  <T> T inTransaction(String description, int isolation, Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(isolation);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException | Error e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new StoreException("could not " + description, e);
    }
  }

  <T> T inTransaction(String description, Work<T> work) {
    return inTransaction(description, Connection.TRANSACTION_READ_COMMITTED, work);
  }
}
