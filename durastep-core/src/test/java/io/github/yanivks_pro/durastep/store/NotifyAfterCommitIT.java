package io.github.yanivks_pro.durastep.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.TestPostgres;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class NotifyAfterCommitIT {

  static final class Echo implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return input;
    }
  }

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private Connection listener;

  @BeforeAll
  static void startPostgres() throws SQLException {
    postgres = TestPostgres.newContainer();
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    try (DurastepEngine migrate = DurastepEngine.builder().dataSource(dataSource).build()) {
      migrate.start();
    }
    execute("CREATE TABLE notes (text text NOT NULL)");
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @BeforeEach
  void listen() throws SQLException {
    execute("TRUNCATE durastep.workflows CASCADE");
    execute("TRUNCATE notes");
    listener = dataSource.getConnection();
    try (Statement statement = listener.createStatement()) {
      statement.execute("LISTEN test");
      statement.execute("LISTEN durastep");
    }
  }

  @AfterEach
  void stopListening() throws SQLException {
    listener.close();
  }

  @Test
  void notificationsAreSentOnlyOnceTheWorkHasCommitted() throws SQLException {
    Transactions transactions = new Transactions(dataSource);
    List<String> seenDuringTheWork = new ArrayList<>();

    transactions.inTransaction(
        "write a note",
        connection -> {
          insertNote(connection);
          transactions.notifyAfterCommit(connection, "test", "a note");
          seenDuringTheWork.addAll(notifications());
          return null;
        });

    assertThat(seenDuringTheWork).isEmpty();
    assertThat(notifications()).containsExactly("a note");
    assertThat(notes()).isEqualTo(1);
  }

  @Test
  void aTransactionThatFailsSendsNothing() throws SQLException {
    Transactions transactions = new Transactions(dataSource);

    assertThatThrownBy(
            () ->
                transactions.inTransaction(
                    "write a note",
                    connection -> {
                      insertNote(connection);
                      transactions.notifyAfterCommit(connection, "test", "a note");
                      throw new IllegalStateException("something broke");
                    }))
        .hasMessage("something broke");

    assertThat(notifications()).isEmpty();
    assertThat(notes()).isZero();
  }

  @Test
  void eachDifferentNotificationIsSentOncePerTransaction() throws SQLException {
    Transactions transactions = new Transactions(dataSource);

    transactions.inTransaction(
        "notify",
        connection -> {
          transactions.notifyAfterCommit(connection, "test", "WORKFLOW:default");
          transactions.notifyAfterCommit(connection, "test", "ACTIVITY:emails");
          transactions.notifyAfterCommit(connection, "test", "WORKFLOW:default");
          return null;
        });

    assertThat(notifications()).containsExactly("WORKFLOW:default", "ACTIVITY:emails");
  }

  @Test
  void workThatCommittedSucceedsEvenWhenTheNotificationCannotBeSent() throws SQLException {
    Transactions transactions = new Transactions(refusingToNotify(dataSource));

    String result =
        transactions.inTransaction(
            "write a note",
            connection -> {
              insertNote(connection);
              transactions.notifyAfterCommit(connection, "test", "a note");
              return "saved";
            });

    assertThat(result).isEqualTo("saved");
    assertThat(notes()).isEqualTo(1);
    assertThat(notifications()).isEmpty();
  }

  @Test
  void everyNewTaskStillWakesTheEngines() throws SQLException {
    DurastepClient client = DurastepClient.connect(dataSource);
    for (int i = 0; i < 5; i++) {
      client.start(Echo.class, "order-" + i, "x");
    }

    assertThat(notifications()).hasSize(5).containsOnly("WORKFLOW:default");
  }

  private List<String> notifications() throws SQLException {
    List<String> received = new ArrayList<>();
    PGNotification[] batch;
    while ((batch = listener.unwrap(PGConnection.class).getNotifications(300)) != null
        && batch.length > 0) {
      for (PGNotification notification : batch) {
        received.add(notification.getParameter());
      }
    }
    return received;
  }

  private static void insertNote(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO notes VALUES ('hello')");
    }
  }

  private static long notes() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT count(*) FROM notes")) {
      row.next();
      return row.getLong(1);
    }
  }

  private static DataSource refusingToNotify(DataSource real) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (source, method, args) -> {
              Object result = invoke(method, real, args);
              return result instanceof Connection connection ? refusing(connection) : result;
            });
  }

  private static Connection refusing(Connection real) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (connection, method, args) -> {
              if (method.getName().equals("prepareStatement")
                  && args[0] instanceof String sql
                  && sql.contains("pg_notify")) {
                throw new SQLException("the notification queue is full");
              }
              return invoke(method, real, args);
            });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
