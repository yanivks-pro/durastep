package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

final class ChangeCommands {

  static final int REFUSED = 1;

  private ChangeCommands() {}

  abstract static class ChangeCommand implements Callable<Integer> {
    final Io io;

    @Mixin Connection connection;

    ChangeCommand(Io io) {
      this.io = io;
    }

    boolean confirmed(boolean yes, String question) {
      if (yes) {
        return true;
      }
      if (!io.interactive()) {
        io.err().println("refusing to " + question + " without --yes when not run in a terminal");
        return false;
      }
      io.out()
          .print(Character.toUpperCase(question.charAt(0)) + question.substring(1) + "? [y/N] ");
      io.out().flush();
      String answer = io.readLine().strip().toLowerCase(Locale.ROOT);
      if (answer.equals("y") || answer.equals("yes")) {
        return true;
      }
      io.err().println("not done");
      return false;
    }
  }

  @Command(
      name = "signal",
      description = "Send a signal with a JSON payload to a running workflow.")
  static final class SignalCommand extends ChangeCommand {
    @Parameters(index = "0", paramLabel = "<id>", description = "The workflow's ID.")
    String workflowId;

    @Parameters(index = "1", paramLabel = "<name>", description = "The signal's name.")
    String name;

    @Parameters(index = "2", paramLabel = "<json>", description = "The payload, as JSON.")
    String payload;

    @Option(
        names = "--request-id",
        paramLabel = "<id>",
        description =
            "Makes retried sends safe: a signal with the same request ID is accepted once.")
    String requestId;

    SignalCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      JsonNode data = Output.parse(payload);
      String request = requestId != null ? requestId : UUID.randomUUID().toString();
      boolean accepted = connection.client(io).signal(workflowId, name, data, request);
      io.out()
          .println(
              accepted
                  ? "sent signal " + name + " to " + workflowId
                  : "signal " + name + " with request ID " + request + " was already sent");
      return 0;
    }
  }

  @Command(name = "cancel", description = "Ask a workflow to stop and clean up after itself.")
  static final class CancelCommand extends ChangeCommand {
    @Parameters(paramLabel = "<id>", description = "The workflow's ID.")
    String workflowId;

    @Option(names = "--yes", description = "Do not ask for confirmation.")
    boolean yes;

    CancelCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      DurastepClient client = connection.client(io);
      client.handle(workflowId);
      if (!confirmed(yes, "cancel " + workflowId)) {
        return REFUSED;
      }
      boolean requested = client.cancel(workflowId);
      io.out()
          .println(
              requested
                  ? "asked " + workflowId + " to cancel; it stops at its next wait point"
                  : workflowId + " has already finished or been asked to cancel");
      return requested ? 0 : REFUSED;
    }
  }

  @Command(
      name = "terminate",
      description = "Stop a workflow at once, without cleanup, for example a blocked one.")
  static final class TerminateCommand extends ChangeCommand {
    @Parameters(paramLabel = "<id>", description = "The workflow's ID.")
    String workflowId;

    @Option(
        names = "--reason",
        required = true,
        paramLabel = "<text>",
        description = "Why, for the audit trail.")
    String reason;

    @Option(names = "--yes", description = "Do not ask for confirmation.")
    boolean yes;

    TerminateCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      DurastepClient client = connection.client(io);
      client.handle(workflowId);
      if (!confirmed(yes, "terminate " + workflowId)) {
        return REFUSED;
      }
      boolean terminated = client.handle(workflowId).terminate(reason);
      io.out()
          .println(terminated ? "terminated " + workflowId : workflowId + " has already finished");
      return terminated ? 0 : REFUSED;
    }
  }

  @Command(
      name = "retry-now",
      description = "Retry blocked workflows now instead of after their backoff.")
  static final class RetryNowCommand extends ChangeCommand {
    @ArgGroup(multiplicity = "1")
    Target target;

    static final class Target {
      @Parameters(paramLabel = "<id>", description = "The blocked workflow's ID.")
      String workflowId;

      @Option(
          names = "--all-blocked",
          required = true,
          description = "Retry every blocked workflow.")
      boolean allBlocked;
    }

    @Option(
        names = "--reason",
        required = true,
        paramLabel = "<text>",
        description = "Why, for the audit trail.")
    String reason;

    @Option(names = "--yes", description = "Do not ask for confirmation of --all-blocked.")
    boolean yes;

    RetryNowCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      DurastepClient client = connection.client(io);
      if (target.allBlocked) {
        if (!confirmed(yes, "retry every blocked workflow")) {
          return REFUSED;
        }
        io.out().println("retrying " + client.retryAllBlocked(reason) + " blocked workflows");
        return 0;
      }
      boolean retried = client.handle(target.workflowId).retryNow(reason);
      io.out()
          .println(
              retried
                  ? "retrying " + target.workflowId + " now"
                  : target.workflowId + " is not blocked");
      return retried ? 0 : REFUSED;
    }
  }

  @Command(name = "rerun", description = "Start a finished workflow again, with the same input.")
  static final class RerunCommand extends ChangeCommand {
    @Parameters(paramLabel = "<id>", description = "The finished workflow's ID.")
    String workflowId;

    @Option(
        names = "--reason",
        required = true,
        paramLabel = "<text>",
        description = "Why, for the audit trail.")
    String reason;

    @Option(names = "--yes", description = "Do not ask for confirmation.")
    boolean yes;

    RerunCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      DurastepClient client = connection.client(io);
      client.handle(workflowId);
      if (!confirmed(yes, "rerun " + workflowId)) {
        return REFUSED;
      }
      io.out().println("started " + client.rerun(workflowId, reason));
      return 0;
    }
  }
}
