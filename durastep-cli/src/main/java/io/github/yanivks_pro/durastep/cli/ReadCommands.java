package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.api.HistoryRecord;
import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowQuery;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.history.Timeline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class ReadCommands {

  private ReadCommands() {}

  abstract static class ReadCommand implements Callable<Integer> {
    final Io io;

    @Mixin Connection connection;

    @Option(names = "--json", description = "Print JSON instead of text.")
    boolean json;

    ReadCommand(Io io) {
      this.io = io;
    }
  }

  @Command(name = "list", description = "List workflows, newest first.")
  static final class ListCommand extends ReadCommand {
    @Option(
        names = "--state",
        paramLabel = "<state>",
        description = "running, completed, failed or cancelled")
    WorkflowState state;

    @Option(names = "--type", paramLabel = "<type>", description = "Only workflows of this type.")
    String type;

    @Option(names = "--blocked", description = "Only blocked workflows.")
    boolean blocked;

    @Option(
        names = "--limit",
        paramLabel = "<n>",
        defaultValue = "50",
        description = "At most this many. Default: ${DEFAULT-VALUE}")
    int limit;

    ListCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      WorkflowQuery query = WorkflowQuery.all().limit(limit);
      if (state != null) {
        query = query.state(state);
      }
      if (type != null) {
        query = query.type(type);
      }
      if (blocked) {
        query = query.blocked(true);
      }
      List<WorkflowDescription> workflows = connection.client(io).list(query);
      if (json) {
        ArrayNode array = Output.array();
        workflows.forEach(workflow -> array.add(describe(workflow)));
        Output.print(io, array);
        return 0;
      }
      if (workflows.isEmpty()) {
        io.out().println("no workflows found");
        return 0;
      }
      List<List<String>> rows = new ArrayList<>();
      for (WorkflowDescription workflow : workflows) {
        rows.add(
            List.of(
                workflow.id(),
                workflow.type(),
                workflow.state().name(),
                workflow.blocked() ? "yes" : "",
                Output.time(workflow.startedAt()),
                workflow.status() == null ? "" : workflow.status()));
      }
      Output.table(io, List.of("ID", "TYPE", "STATE", "BLOCKED", "STARTED", "STATUS"), rows);
      return 0;
    }
  }

  @Command(name = "show", description = "Show a workflow and what has happened to it.")
  static final class ShowCommand extends ReadCommand {
    @Parameters(paramLabel = "<id>", description = "The workflow's ID.")
    String workflowId;

    ShowCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      WorkflowHandle<Object> handle = connection.client(io).handle(workflowId);
      WorkflowDescription workflow = handle.describe();
      Optional<String> error = handle.blockedError();
      List<HistoryRecord> history = handle.history();
      if (json) {
        ObjectNode result = Output.object();
        result.set("workflow", describe(workflow));
        result.put("error", error.orElse(null));
        ArrayNode timeline = result.putArray("timeline");
        for (Timeline.Entry entry : Timeline.of(history).entries()) {
          timeline.add(
              Output.object()
                  .put("seq", entry.seq())
                  .put("at", Output.iso(entry.at()))
                  .put("type", entry.type())
                  .put("summary", entry.text()));
        }
        Output.print(io, result);
        return 0;
      }
      io.out()
          .println(
              String.join(
                      "   ",
                      workflow.id(),
                      workflow.type(),
                      workflow.state().name(),
                      workflow.blocked() ? "BLOCKED" : "",
                      "started " + Output.time(workflow.startedAt()),
                      workflow.closedAt() == null
                          ? ""
                          : "closed " + Output.time(workflow.closedAt()))
                  .replaceAll(" {4,}", "   ")
                  .strip());
      if (workflow.status() != null) {
        io.out().println("status:  " + workflow.status());
      }
      error.ifPresent(
          message -> io.out().println("error:   " + message + "   (retrying with backoff)"));
      io.out().println();
      printTimeline(history);
      return 0;
    }

    private void printTimeline(List<HistoryRecord> history) {
      if (history.isEmpty()) {
        return;
      }
      Instant first = history.getFirst().recordedAt();
      for (Timeline.Entry entry : Timeline.of(history).entries()) {
        io.out()
            .printf(
                "  #%-3d %8s  %s%n",
                entry.seq(),
                "+" + Output.seconds(Duration.between(first, entry.at())).replace(" s", "s"),
                entry.text());
      }
    }
  }

  @Command(name = "history", description = "Show a workflow's raw history events.")
  static final class HistoryCommand extends ReadCommand {
    private static final int DETAILS_WIDTH = 100;

    @Parameters(paramLabel = "<id>", description = "The workflow's ID.")
    String workflowId;

    HistoryCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      List<HistoryRecord> history = connection.client(io).handle(workflowId).history();
      if (json) {
        ArrayNode array = Output.array();
        for (HistoryRecord event : history) {
          array.add(
              Output.object()
                  .put("seq", event.seq())
                  .put("at", Output.iso(event.recordedAt()))
                  .put("type", event.type())
                  .set("data", Output.parse(event.json())));
        }
        Output.print(io, array);
        return 0;
      }
      List<List<String>> rows = new ArrayList<>();
      for (HistoryRecord event : history) {
        String details = Output.compact(event.json());
        rows.add(
            List.of(
                String.valueOf(event.seq()),
                Output.time(event.recordedAt()),
                event.type(),
                details.length() > DETAILS_WIDTH
                    ? details.substring(0, DETAILS_WIDTH - 3) + "..."
                    : details));
      }
      Output.table(io, List.of("SEQ", "TIME", "TYPE", "DETAILS"), rows);
      return 0;
    }
  }

  @Command(name = "stats", description = "Count workflows and the work waiting for an engine.")
  static final class StatsCommand extends ReadCommand {
    StatsCommand(Io io) {
      super(io);
    }

    @Override
    public Integer call() {
      EngineStats stats = connection.client(io).stats();
      if (json) {
        ObjectNode result =
            Output.object()
                .put("running", stats.running())
                .put("blocked", stats.blocked())
                .put("waitingDecisions", stats.readyDecisions())
                .put("oldestWaitingMillis", stats.oldestReadyWork().toMillis());
        ObjectNode activities = result.putObject("waitingActivities");
        stats.readyActivities().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> activities.put(entry.getKey(), entry.getValue()));
        Output.print(io, result);
        return 0;
      }
      List<String> perQueue = new ArrayList<>();
      stats.readyActivities().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> perQueue.add(entry.getKey() + ": " + entry.getValue()));
      io.out().println("running workflows    " + stats.running());
      io.out().println("blocked              " + stats.blocked());
      io.out().println("waiting decisions    " + stats.readyDecisions());
      io.out()
          .println(
              "waiting activities   " + (perQueue.isEmpty() ? "0" : String.join(", ", perQueue)));
      io.out().println("oldest waiting work  " + Output.seconds(stats.oldestReadyWork()));
      return 0;
    }
  }

  static ObjectNode describe(WorkflowDescription workflow) {
    return Output.object()
        .put("id", workflow.id())
        .put("type", workflow.type())
        .put("state", workflow.state().name())
        .put("blocked", workflow.blocked())
        .put("status", workflow.status())
        .put("startedAt", Output.iso(workflow.startedAt()))
        .put("closedAt", Output.iso(workflow.closedAt()));
  }
}
