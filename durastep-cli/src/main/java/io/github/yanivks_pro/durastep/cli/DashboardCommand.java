package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.dashboard.DurastepDashboard;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "dashboard", description = "Serve a read-only web dashboard until stopped.")
final class DashboardCommand implements Callable<Integer> {

  private final Io io;

  @Mixin Connection connection;

  @Option(
      names = "--port",
      paramLabel = "<port>",
      defaultValue = "" + DurastepDashboard.DEFAULT_PORT,
      description = "Port to listen on. Default: ${DEFAULT-VALUE}")
  int port;

  @Option(
      names = "--bind",
      paramLabel = "<address>",
      defaultValue = "127.0.0.1",
      description =
          "Address to listen on. Default: ${DEFAULT-VALUE}, so only this machine can open it")
  String bind;

  DashboardCommand(Io io) {
    this.io = io;
  }

  @Override
  public Integer call() throws UnknownHostException {
    InetAddress address = InetAddress.getByName(bind);
    try (DurastepDashboard dashboard =
        DurastepDashboard.start(connection.client(io), new InetSocketAddress(address, port))) {
      if (!address.isLoopbackAddress()) {
        io.err()
            .println(
                "warning: the dashboard has no login; anyone who can reach "
                    + bind
                    + " can see workflow IDs, types, statuses and errors");
      }
      io.out().println("Dashboard at " + dashboard.uri() + " (read-only). Press Ctrl+C to stop.");
      io.out().flush();
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return 0;
  }
}
