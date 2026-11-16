package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.activity.Backoff;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NotificationListener {

  private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

  private static final int WAIT_MILLIS = 500;
  private static final RetryPolicy RECONNECT =
      new RetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1), Duration.ofSeconds(30), List.of());

  private final DataSource dataSource;
  private final String channel;
  private final BiConsumer<TaskKind, String> onWork;

  private volatile boolean running;
  private Thread thread;

  public NotificationListener(
      DataSource dataSource, String channel, BiConsumer<TaskKind, String> onWork) {
    this.dataSource = dataSource;
    this.channel = channel;
    this.onWork = onWork;
  }

  public synchronized void start() {
    running = true;
    thread = Thread.ofVirtual().name("durastep-listener-" + channel).start(this::listen);
  }

  public synchronized void stop() throws InterruptedException {
    running = false;
    if (thread != null && !thread.join(Duration.ofMillis(WAIT_MILLIS * 4L))) {
      thread.interrupt();
      thread.join();
    }
  }

  private void listen() {
    int failures = 0;
    while (running) {
      try (Connection connection = dataSource.getConnection()) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + channel);
        }
        if (failures > 0) {
          log.info("listening for new work again after {} failed attempts", failures);
        }
        failures = 0;
        PGConnection postgres = connection.unwrap(PGConnection.class);
        while (running) {
          PGNotification[] notifications = postgres.getNotifications(WAIT_MILLIS);
          if (notifications == null) {
            continue;
          }
          for (PGNotification notification : notifications) {
            dispatch(notification.getParameter());
          }
        }
      } catch (SQLException e) {
        if (!running) {
          return;
        }
        failures++;
        if (failures == 1) {
          log.warn(
              "lost the connection that listens for new work; polling continues meanwhile: {}",
              e.toString());
        }
        try {
          Thread.sleep(Backoff.delayAfter(RECONNECT, failures, ThreadLocalRandom.current()));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void dispatch(String payload) {
    int separator = payload.indexOf(':');
    if (separator < 0) {
      return;
    }
    try {
      onWork.accept(
          TaskKind.valueOf(payload.substring(0, separator)), payload.substring(separator + 1));
    } catch (IllegalArgumentException e) {
      log.debug("ignored an unexpected notification {}", payload);
    }
  }
}
