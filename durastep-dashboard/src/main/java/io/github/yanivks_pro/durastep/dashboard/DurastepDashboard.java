package io.github.yanivks_pro.durastep.dashboard;

import com.sun.net.httpserver.HttpServer;
import io.github.yanivks_pro.durastep.api.DurastepClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A read-only web dashboard for the workflows in a Durastep database: an overview of counts and
 * waiting work, and a list of workflows to search and filter.
 *
 * <p>The dashboard has no login and no actions. By default it listens only on this machine's
 * loopback address; to change workflows, use the CLI or the client.
 *
 * <pre>{@code
 * try (DurastepDashboard dashboard = DurastepDashboard.start(client, 8099)) {
 *   System.out.println("Open " + dashboard.uri());
 *   ...
 * }
 * }</pre>
 */
public final class DurastepDashboard implements AutoCloseable {

  /** The port the CLI uses when none is given. */
  public static final int DEFAULT_PORT = 8099;

  private final HttpServer server;
  private final ExecutorService executor;
  private final InetAddress host;

  private DurastepDashboard(HttpServer server, ExecutorService executor, InetAddress host) {
    this.server = server;
    this.executor = executor;
    this.host = host;
  }

  /**
   * Starts a dashboard on this machine's loopback address, so only this machine can open it.
   *
   * @param client the client the dashboard reads from
   * @param port the port to listen on, or 0 for any free port
   * @return the running dashboard
   * @throws UncheckedIOException if the port cannot be opened
   */
  public static DurastepDashboard start(DurastepClient client, int port) {
    return start(client, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
  }

  /**
   * Starts a dashboard on the given address. Anyone who can reach the address can see workflow IDs,
   * types, statuses and error messages, so choose it with care.
   *
   * @param client the client the dashboard reads from
   * @param address the address and port to listen on
   * @return the running dashboard
   * @throws IllegalArgumentException if the address has not been resolved
   * @throws UncheckedIOException if the address cannot be opened
   */
  public static DurastepDashboard start(DurastepClient client, InetSocketAddress address) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(address, "address");
    if (address.isUnresolved()) {
      throw new IllegalArgumentException("the dashboard's address is unknown: " + address);
    }
    HttpServer server;
    try {
      server = HttpServer.create(address, 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not open the dashboard on " + address, e);
    }
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", new Router(client));
    server.start();
    return new DurastepDashboard(server, executor, address.getAddress());
  }

  /**
   * Returns the address to open in a browser, such as {@code http://127.0.0.1:8099/}: the address
   * the dashboard was asked to listen on, with the port it actually listens on.
   *
   * @return the dashboard's address
   */
  public URI uri() {
    String shown = host.getHostAddress();
    if (shown.contains(":")) {
      shown = "[" + shown + "]";
    }
    return URI.create("http://" + shown + ":" + server.getAddress().getPort() + "/");
  }

  /** Stops the dashboard and closes its port. */
  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }
}
