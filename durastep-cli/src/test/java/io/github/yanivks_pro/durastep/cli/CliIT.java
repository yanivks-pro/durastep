package io.github.yanivks_pro.durastep.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.testkit.WorkflowReplayException;
import io.github.yanivks_pro.durastep.testkit.WorkflowReplayer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CliIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Path SNAPSHOTS = Path.of("src", "test", "resources", "snapshots");
  private static final boolean UPDATE_SNAPSHOTS = Boolean.getBoolean("snapshots.update");

  interface Shop {
    String charge(String order);
  }

  static final class Order implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      ctx.setStatus("charging the card");
      String charge = ctx.activities(Shop.class).charge(order);
      ctx.setStatus("charged");
      return charge;
    }
  }

  static final class Greeter implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String greeting) {
      ctx.setStatus("waiting for a name");
      return greeting + " " + ctx.awaitSignal("name", String.class);
    }
  }

  static final class Broken implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      throw new IllegalStateException("a bug in version 1.4.1");
    }
  }

  record Result(int exitCode, String out, String err) {}

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;
  private static DurastepEngine engine;
  private static DurastepClient client;

  @BeforeAll
  static void startEngine() {
    postgres = new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:16-alpine"));
    postgres.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(Order.class, Order::new)
            .workflow(Greeter.class, Greeter::new)
            .workflow(Broken.class, Broken::new)
            .activities(Shop.class, order -> "ch_" + order)
            .build();
    engine.start();
    client = engine.client();
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
    postgres.stop();
  }

  @BeforeEach
  void emptyTables() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE durastep.workflows CASCADE");
    }
  }

  @Test
  void listShowsWorkflowsNewestFirstAndFiltersThem() throws Exception {
    order("order-1");
    blocked("broken-1");

    Result all = cli("list");
    Result blockedOnly = cli("list", "--blocked");
    Result completed = cli("list", "--state", "completed");

    assertThat(all.exitCode()).isZero();
    assertThat(all.out().lines()).first().asString().startsWith("ID").contains("STATE", "STATUS");
    assertThat(all.out()).containsSubsequence("broken-1", "order-1");
    assertThat(blockedOnly.out()).contains("broken-1").doesNotContain("order-1");
    assertThat(completed.out()).contains("order-1", "charged").doesNotContain("broken-1");
    assertThat(cli("list", "--type", "Nothing").out()).isEqualTo("no workflows found\n");
    matchesSnapshot("list", cli("list", "--json"));
  }

  @Test
  void showPrintsTheWorkflowItsErrorAndAReadableTimeline() throws Exception {
    order("order-1");
    blocked("broken-1");

    Result order = cli("show", "order-1");
    Result broken = cli("show", "broken-1");

    assertThat(order.out())
        .contains("order-1   Order   COMPLETED", "status:  charged")
        .containsSubsequence(
            "#1",
            "started",
            "#2",
            "scheduled Shop.charge",
            "#3",
            "Shop.charge completed",
            "#4",
            "completed");
    assertThat(broken.out())
        .contains("BLOCKED", "error:   java.lang.IllegalStateException: a bug in version 1.4.1");
    matchesSnapshot("show", cli("show", "order-1", "--json"));
  }

  @Test
  void historyPrintsTheRawEvents() throws Exception {
    order("order-1");

    Result history = cli("history", "order-1");

    assertThat(history.out())
        .contains("SEQ", "WORKFLOW_STARTED", "ACTIVITY_SCHEDULED", "{\"result\":\"ch_order-1\",");
    matchesSnapshot("history", cli("history", "order-1", "--json"));
  }

  static final class ChangedOrder implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      ctx.sleep(Duration.ofSeconds(1));
      return ctx.activities(Shop.class).charge(order);
    }
  }

  @Test
  void aSavedHistoryCanBeReplayedAgainstNewCodeWithTheTestkit(@TempDir Path histories)
      throws Exception {
    order("order-1");
    Files.writeString(histories.resolve("order-1.json"), cli("history", "order-1", "--json").out());

    WorkflowReplayer.of(Order.class, Order::new).replay(histories);
    assertThatThrownBy(
            () -> WorkflowReplayer.of(ChangedOrder.class, ChangedOrder::new).replay(histories))
        .isInstanceOf(WorkflowReplayException.class)
        .hasMessageContaining("called sleep at step 1, but its history has Shop.charge there");
  }

  @Test
  void statsCountWorkflowsAndWaitingWork() throws Exception {
    order("order-1");
    blocked("broken-1");
    client.start(Greeter.class, "greeter-1", "hello");
    await().atMost(WAIT).until(() -> client.stats().readyDecisions() == 0);

    Result stats = cli("stats");

    assertThat(stats.out()).contains("running workflows    2", "blocked              1");
    matchesSnapshot("stats", cli("stats", "--json"));
  }

  @Test
  void aSignalReachesTheWorkflowOncePerRequestId() throws Exception {
    client.start(Greeter.class, "greeter-1", "hello");

    Result sent = cli("signal", "greeter-1", "name", "\"Ada\"", "--request-id", "r1");
    Result again = cli("signal", "greeter-1", "name", "\"Bob\"", "--request-id", "r1");

    assertThat(sent.out()).isEqualTo("sent signal name to greeter-1\n");
    assertThat(again.out()).contains("was already sent");
    assertThat(client.handle("greeter-1", Greeter.class).result(WAIT)).isEqualTo("hello Ada");
  }

  @Test
  void terminateNeedsAReasonAndAConfirmation() throws Exception {
    blocked("broken-1");

    Result noReason = cli("terminate", "broken-1");
    Result notInATerminal = cli("terminate", "broken-1", "--reason", "fraud");
    Result answeredNo = interactive("n\n", "terminate", "broken-1", "--reason", "fraud");
    Result answeredYes =
        interactive("y\n", "terminate", "broken-1", "--reason", "fraud", "--as", "alice");

    assertThat(noReason.exitCode()).isEqualTo(2);
    assertThat(noReason.err()).contains("Missing required option: '--reason=<text>'");
    assertThat(notInATerminal.exitCode()).isEqualTo(1);
    assertThat(notInATerminal.err()).contains("refusing to terminate broken-1 without --yes");
    assertThat(answeredNo.exitCode()).isEqualTo(1);
    assertThat(answeredNo.out()).contains("Terminate broken-1? [y/N]");
    assertThat(answeredYes.exitCode()).isZero();
    assertThat(answeredYes.out()).contains("terminated broken-1");
    assertThatThrownBy(() -> client.handle("broken-1").result(WAIT))
        .isInstanceOfSatisfying(
            WorkflowCancelledException.class,
            e -> assertThat(e.reason()).contains("terminated by alice: fraud"));
    assertThat(cli("show", "broken-1").out()).contains("terminate by alice: fraud");
  }

  @Test
  void retryNowRetriesOneOrAllBlockedWorkflows() throws Exception {
    blocked("broken-1");
    blocked("broken-2");
    order("order-1");

    Result one = cli("retry-now", "broken-1", "--reason", "fix deployed");
    Result notBlocked = cli("retry-now", "order-1", "--reason", "just in case");
    Result all = cli("retry-now", "--all-blocked", "--reason", "fix deployed", "--yes");
    Result neither = cli("retry-now", "--reason", "fix deployed");

    assertThat(one.out()).isEqualTo("retrying broken-1 now\n");
    assertThat(notBlocked.exitCode()).isEqualTo(1);
    assertThat(all.out()).contains("blocked workflows");
    assertThat(neither.exitCode()).isEqualTo(2);
  }

  @Test
  void cancelAndRerunAskBeforeTheyChangeAnything() throws Exception {
    client.start(Greeter.class, "greeter-1", "hello");
    client.start(Greeter.class, "greeter-2", "hi");
    order("order-1");

    Result cancelled = cli("cancel", "greeter-1", "--yes");
    Result rerun = cli("rerun", "order-1", "--reason", "reprocess", "--yes");
    Result rerunWhileRunning = cli("rerun", "greeter-2", "--reason", "too early", "--yes");
    Result cancelNotConfirmed = cli("cancel", "greeter-2");

    assertThat(cancelled.exitCode()).isZero();
    assertThatThrownBy(() -> client.handle("greeter-1").result(WAIT))
        .isInstanceOf(WorkflowCancelledException.class);
    assertThat(rerun.out()).isEqualTo("started order-1-rerun-1\n");
    assertThat(client.handle("order-1-rerun-1").result(WAIT)).isEqualTo("ch_order-1");
    assertThat(rerunWhileRunning.exitCode()).isEqualTo(1);
    assertThat(rerunWhileRunning.err()).contains("is still running");
    assertThat(cancelNotConfirmed.exitCode()).isEqualTo(1);
    assertThat(client.handle("greeter-2").describe().state()).isEqualTo(WorkflowState.RUNNING);
  }

  @Test
  void problemsAreReportedClearlyWithExitCodeOne() {
    Result unknown = cli("show", "nope");
    Result badDatabase =
        cliWith(
            Map.of("DURASTEP_DB_URL", "jdbc:postgresql://localhost:1/nowhere"), false, "", "stats");

    assertThat(unknown.exitCode()).isEqualTo(1);
    assertThat(unknown.err()).isEqualTo("error: no workflow nope exists\n");
    assertThat(badDatabase.exitCode()).isEqualTo(1);
    assertThat(badDatabase.err()).startsWith("error: could not read statistics");
  }

  record Background(Thread thread, StringWriter out, StringWriter err, AtomicInteger exitCode) {
    URI printedAddress() {
      await().atMost(WAIT).until(() -> out.toString().contains("(read-only)"));
      Matcher printed =
          Pattern.compile("Dashboard at (http://\\S+/) \\(read-only\\)").matcher(out.toString());
      assertThat(printed.find()).as(out.toString()).isTrue();
      return URI.create(printed.group(1));
    }

    int stop() throws InterruptedException {
      thread.interrupt();
      assertThat(thread.join(WAIT)).isTrue();
      return exitCode.get();
    }
  }

  @Test
  void dashboardServesTheDashboardOnThisMachineUntilStopped() throws Exception {
    order("order-1");
    Background dashboard = inBackground("dashboard", "--port", "0");
    URI address = dashboard.printedAddress();

    assertThat(address.getHost()).isEqualTo("127.0.0.1");
    HttpResponse<String> page = get(address.resolve("/workflows"));
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body())
        .contains("<td class=\"id\"><a href=\"/workflows/order-1\">order-1</a></td>");
    assertThat(get(address.resolve("/workflows/order-1")).body()).contains("<h1>order-1</h1>");
    assertThat(dashboard.err().toString()).isEmpty();

    assertThat(dashboard.stop()).isZero();
    assertThatThrownBy(() -> get(address)).isInstanceOf(IOException.class);
  }

  @Test
  void dashboardWarnsWhenOtherMachinesCanReachIt() throws Exception {
    Background dashboard = inBackground("dashboard", "--port", "0", "--bind", "0.0.0.0");
    URI address = dashboard.printedAddress();

    assertThat(address.getHost()).isEqualTo("0.0.0.0");
    assertThat(dashboard.err().toString())
        .contains(
            "warning: the dashboard has no login; anyone who can reach 0.0.0.0 can see workflow"
                + " IDs, types, statuses and errors");
    assertThat(dashboard.stop()).isZero();
  }

  @Test
  void dashboardRefusesAnAddressThatDoesNotExist() {
    Result result = cli("dashboard", "--bind", "256.0.0.1");

    assertThat(result.exitCode()).isEqualTo(DurastepCli.FAILED);
    assertThat(result.err()).contains("256.0.0.1");
  }

  private static Background inBackground(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    AtomicInteger exitCode = new AtomicInteger(-1);
    Io io =
        new Io(
            new BufferedReader(new StringReader("")),
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            Map.of(
                "DURASTEP_DB_URL", postgres.getJdbcUrl(),
                "DURASTEP_DB_USER", postgres.getUsername(),
                "DURASTEP_DB_PASSWORD", postgres.getPassword()),
            false);
    Thread thread = Thread.ofVirtual().start(() -> exitCode.set(DurastepCli.run(io, args)));
    return new Background(thread, out, err, exitCode);
  }

  private static HttpResponse<String> get(URI address) throws Exception {
    return HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(address).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void order(String id) throws Exception {
    client.start(Order.class, id, id).result(WAIT);
  }

  private static void blocked(String id) {
    client.start(Broken.class, id, id);
    await().atMost(WAIT).until(() -> client.handle(id).describe().blocked());
  }

  private static Result cli(String... args) {
    return cliWith(Map.of(), false, "", args);
  }

  private static Result interactive(String input, String... args) {
    return cliWith(Map.of(), true, input, args);
  }

  private static Result cliWith(
      Map<String, String> overrides, boolean interactive, String input, String... args) {
    Map<String, String> environment =
        new HashMap<>(
            Map.of(
                "DURASTEP_DB_URL", postgres.getJdbcUrl(),
                "DURASTEP_DB_USER", postgres.getUsername(),
                "DURASTEP_DB_PASSWORD", postgres.getPassword()));
    environment.putAll(overrides);
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exitCode =
        DurastepCli.run(
            new Io(
                new BufferedReader(new StringReader(input)),
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                environment,
                interactive),
            args);
    return new Result(exitCode, out.toString(), err.toString());
  }

  private static void matchesSnapshot(String name, Result result) throws Exception {
    assertThat(result.exitCode()).isZero();
    JsonNode actual = withoutTimes(Output.parse(result.out()));
    Path file = SNAPSHOTS.resolve(name + ".json");
    if (UPDATE_SNAPSHOTS) {
      Files.writeString(file, Output.JSON.writeValueAsString(actual) + "\n");
      return;
    }
    assertThat(actual).isEqualTo(Output.parse(Files.readString(file)));
  }

  private static JsonNode withoutTimes(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String field : List.copyOf(object.propertyNames())) {
        if (field.endsWith("At") || field.equals("at") || field.endsWith("Millis")) {
          if (!object.get(field).isNull()) {
            object.put(field, "<varies>");
          }
        } else {
          withoutTimes(object.get(field));
        }
      }
    } else if (node.isArray()) {
      node.forEach(CliIT::withoutTimes);
    }
    return node;
  }
}
