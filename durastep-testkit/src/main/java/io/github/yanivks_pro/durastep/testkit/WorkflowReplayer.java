package io.github.yanivks_pro.durastep.testkit;

import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.OperatorAction;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCancelled;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.WorkflowBlockedException;
import io.github.yanivks_pro.durastep.runtime.WorkflowDefinition;
import io.github.yanivks_pro.durastep.runtime.WorkflowRuntime;
import io.github.yanivks_pro.durastep.store.HistoryDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays saved workflow histories against the current code, so that a change that would break
 * running workflows fails in a test instead of after a deploy.
 *
 * <p>Save histories with the command-line tool, one file per workflow, named after its ID:
 *
 * <pre>
 * durastep history order-42 --json &gt; src/test/resources/histories/order-42.json
 * </pre>
 *
 * <p>Then replay them in a test:
 *
 * <pre>{@code
 * @Test
 * void theNewCodeStillReplaysSavedOrders() {
 *   WorkflowReplayer.of(OrderWorkflow.class, OrderWorkflow::new)
 *       .replay(Path.of("src/test/resources/histories"));
 * }
 * }</pre>
 *
 * <p>A history fails if the code makes a different call than the history recorded, or throws where
 * it did not before. A history of a workflow that finished must also finish the same way: with an
 * equal result, a failure or a cancellation. Like the engine, the replayer compares which
 * activities, timers and signal waits are called and in what order, not the arguments passed to
 * them, so a change of arguments alone does not fail. Use {@link
 * io.github.yanivks_pro.durastep.api.WorkflowContext#version} to keep old code paths for histories
 * that went past a change.
 */
public final class WorkflowReplayer {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final WorkflowDefinition<?, ?> definition;
  private final WorkflowRuntime runtime = new WorkflowRuntime(new JsonPayloadConverter());

  private WorkflowReplayer(WorkflowDefinition<?, ?> definition) {
    this.definition = definition;
  }

  /**
   * Creates a replayer for a workflow class.
   *
   * @param workflowClass the workflow class under test
   * @param factory creates a fresh instance for every replay, as the engine does
   * @param <I> the workflow's input type
   * @param <O> the workflow's result type
   * @param <W> the workflow's class
   * @return a replayer for that workflow
   */
  public static <I, O, W extends Workflow<I, O>> WorkflowReplayer of(
      Class<W> workflowClass, Supplier<W> factory) {
    return new WorkflowReplayer(WorkflowDefinition.of(workflowClass, factory));
  }

  /**
   * Replays one history file, or every {@code .json} file in a directory.
   *
   * @param historyFileOrDirectory a file written by {@code durastep history <id> --json}, or a
   *     directory of such files
   * @throws WorkflowReplayException if any history no longer replays
   */
  public void replay(Path historyFileOrDirectory) {
    List<Path> files = historyFiles(historyFileOrDirectory);
    List<String> failures = new ArrayList<>();
    for (Path file : files) {
      String name = file.getFileName().toString();
      String workflowId = name.substring(0, name.length() - ".json".length());
      String json;
      try {
        json = Files.readString(file);
      } catch (IOException e) {
        throw new UncheckedIOException("could not read " + file, e);
      }
      check(name, workflowId, json).ifPresent(failures::add);
    }
    if (!failures.isEmpty()) {
      throw new WorkflowReplayException(
          failures.size()
              + " of "
              + files.size()
              + " histories no longer replay with "
              + definition.type()
              + ":",
          failures);
    }
  }

  /**
   * Replays one history given as JSON, in the format of {@code durastep history <id> --json}.
   *
   * @param workflowId the workflow's ID, which replay needs for {@code ctx.random()}
   * @param historyJson the history
   * @throws WorkflowReplayException if the history no longer replays
   */
  public void replay(String workflowId, String historyJson) {
    Objects.requireNonNull(workflowId, "workflowId");
    check(workflowId, workflowId, historyJson)
        .ifPresent(
            failure -> {
              throw new WorkflowReplayException(
                  "the history of "
                      + workflowId
                      + " no longer replays with "
                      + definition.type()
                      + ":",
                  List.of(failure));
            });
  }

  private Optional<String> check(String name, String workflowId, String json) {
    List<HistoryEntry> history;
    try {
      history = parse(json);
    } catch (JacksonException | IllegalArgumentException e) {
      return Optional.of(
          name + ": not a history saved with durastep history --json: " + e.getMessage());
    }
    if (history.isEmpty()) {
      return Optional.of(name + ": the history is empty");
    }
    HistoryEntry last = history.getLast();
    boolean finished =
        last.event() instanceof WorkflowCompleted
            || last.event() instanceof WorkflowFailed
            || last.event() instanceof WorkflowCancelled;
    boolean terminated =
        history.stream()
            .anyMatch(
                entry ->
                    entry.event() instanceof OperatorAction action
                        && action.action().equals("terminate"));
    List<HistoryEntry> toReplay = finished ? history.subList(0, history.size() - 1) : history;
    List<Command> commands;
    try {
      commands = runtime.decide(definition, workflowId, toReplay);
    } catch (NonDeterminismException e) {
      return Optional.of(name + ": " + e.getMessage());
    } catch (WorkflowBlockedException e) {
      return Optional.of(name + ": the code now throws " + e.getCause());
    } catch (IllegalArgumentException | IllegalStateException e) {
      return Optional.of(name + ": " + e.getMessage());
    }
    if (!finished || terminated) {
      return Optional.empty();
    }
    return sameEnding(name, last, commands);
  }

  private static Optional<String> sameEnding(
      String name, HistoryEntry last, List<Command> commands) {
    return switch (last.event()) {
      case WorkflowCompleted completed -> sameResult(name, completed, commands);
      case WorkflowFailed ignored ->
          commands.stream().anyMatch(FailWorkflow.class::isInstance)
              ? Optional.empty()
              : Optional.of(
                  name + ": failed in the history, but the code now does " + describe(commands));
      default ->
          commands.stream().anyMatch(CancelWorkflow.class::isInstance)
              ? Optional.empty()
              : Optional.of(
                  name
                      + ": was cancelled in the history, but the code now does "
                      + describe(commands));
    };
  }

  private static Optional<String> sameResult(
      String name, WorkflowCompleted completed, List<Command> commands) {
    for (Command command : commands) {
      if (command instanceof CompleteWorkflow complete) {
        JsonNode expected = JSON.readTree(completed.result().json());
        JsonNode actual = JSON.readTree(complete.result().json());
        return expected.equals(actual)
            ? Optional.empty()
            : Optional.of(
                name
                    + ": completed with "
                    + actual
                    + ", but the history completed with "
                    + expected);
      }
    }
    return Optional.of(
        name + ": completed in the history, but the code now does " + describe(commands));
  }

  private static String describe(List<Command> commands) {
    return commands.isEmpty()
        ? "nothing"
        : commands.stream().map(command -> command.getClass().getSimpleName()).toList().toString();
  }

  private static List<HistoryEntry> parse(String json) {
    JsonNode events = JSON.readTree(json);
    if (!events.isArray()) {
      throw new IllegalArgumentException("expected a JSON array of events");
    }
    List<HistoryEntry> history = new ArrayList<>();
    for (JsonNode event : events) {
      history.add(
          HistoryDecoder.decode(
              event.required("seq").asInt(),
              Instant.parse(event.required("at").asString()),
              event.required("type").asString(),
              JSON.writeValueAsString(event.required("data"))));
    }
    return history;
  }

  private static List<Path> historyFiles(Path fileOrDirectory) {
    if (!Files.isDirectory(fileOrDirectory)) {
      return List.of(fileOrDirectory);
    }
    try (Stream<Path> files = Files.list(fileOrDirectory)) {
      List<Path> histories =
          files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
      if (histories.isEmpty()) {
        throw new WorkflowReplayException(
            "nothing to replay", List.of("no .json history files in " + fileOrDirectory));
      }
      return histories;
    } catch (IOException e) {
      throw new UncheckedIOException("could not list " + fileOrDirectory, e);
    }
  }
}
