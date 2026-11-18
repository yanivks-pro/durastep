package io.github.yanivks_pro.durastep.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DashboardIT {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Pattern ID_CELL =
      Pattern.compile("<td class=\"id\"><a href=\"([^\"]*)\">(.*?)</a></td>");
  private static final byte[] IPV6_LOOPBACK = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
  private static final String SLASHED_ID = "team/a b+c";
  private static final String EVIL_ID = "<b>evil</b>&co";
  private static final String EVIL_STATUS = "<img src=x onerror=alert(1)>";

  static final class Echo implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      return input;
    }
  }

  static final class Waiting implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String status) {
      ctx.setStatus(status);
      return ctx.awaitSignal("go", String.class);
    }
  }

  interface Shop {
    String pack(String order);
  }

  static final class Shipping implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      String box = ctx.activities(Shop.class).pack(order);
      ctx.sleep(Duration.ofMillis(200));
      return box + ctx.awaitSignal("go", String.class, Duration.ofMinutes(1)).orElse("late");
    }
  }

  static final class Refuses implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      throw new WorkflowFailedException("refused " + input);
    }
  }

  static final class Broken implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String input) {
      throw new IllegalStateException("a bug in version 1.4.1");
    }
  }

  private static PostgreSQLContainer postgres;
  private static DurastepEngine engine;
  private static DurastepDashboard dashboard;
  private static final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void startEverything() throws Exception {
    postgres = new PostgreSQLContainer(System.getProperty("postgres.image", "postgres:16-alpine"));
    postgres.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(Echo.class, Echo::new)
            .workflow(Waiting.class, Waiting::new)
            .workflow(Broken.class, Broken::new)
            .workflow(Refuses.class, Refuses::new)
            .workflow(Shipping.class, Shipping::new)
            .activities(Shop.class, order -> "box for " + order + " ")
            .build();
    engine.start();
    DurastepClient client = engine.client();
    for (int i = 0; i < 100; i++) {
      client.start(Waiting.class, "bulk-%03d".formatted(i), "waiting for a go");
    }
    WorkflowHandle<String> shipping = client.start(Shipping.class, "ship-1", "order-9");
    await().atMost(WAIT).until(() -> client.handle("ship-1").history().size() >= 6);
    client.signal("ship-1", "go", "and go");
    assertThat(shipping.result(WAIT)).isEqualTo("box for order-9 and go");
    client.start(Echo.class, SLASHED_ID, "x").result(WAIT);
    client.start(Refuses.class, "refused-1", "x");
    await()
        .atMost(WAIT)
        .until(() -> client.handle("refused-1").describe().state() == WorkflowState.FAILED);
    client.start(Echo.class, "order-1", "one").result(WAIT);
    client.start(Echo.class, "order-2", "two").result(WAIT);
    client.start(Waiting.class, "cancelled-1", "waiting").cancel();
    client.start(Waiting.class, EVIL_ID, EVIL_STATUS);
    client.start(Broken.class, "broken-1", "x");
    await().atMost(WAIT).until(() -> client.handle("broken-1").describe().blocked());
    await()
        .atMost(WAIT)
        .until(() -> client.handle("cancelled-1").describe().state() == WorkflowState.CANCELLED);
    await()
        .atMost(WAIT)
        .until(() -> EVIL_STATUS.equals(client.handle(EVIL_ID).describe().status()));
    dashboard = DurastepDashboard.start(client, 0);
  }

  @AfterAll
  static void stopEverything() {
    dashboard.close();
    engine.close();
    postgres.stop();
  }

  @Test
  void theOverviewCountsWorkflowsByStateAndListsTheBlockedOnes() throws Exception {
    HttpResponse<String> overview = get("/");

    assertThat(overview.statusCode()).isEqualTo(200);
    assertThat(overview.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
    assertThat(overview.body())
        .contains(card("", "/workflows?state=RUNNING", 102, "Running"))
        .contains(card(" warn", "/workflows?blocked=yes", 1, "Blocked"))
        .contains(card("", "/workflows?state=COMPLETED", 4, "Completed"))
        .contains(card(" bad", "/workflows?state=FAILED", 1, "Failed"))
        .contains(card("", "/workflows?state=CANCELLED", 1, "Cancelled"))
        .contains("<h2>Waiting for an engine</h2>")
        .contains("<meta http-equiv=\"refresh\" content=\"5\">")
        .contains("<a href=\"/\" aria-current=\"page\">Overview</a>");
    assertThat(ids(overview.body())).containsExactly("broken-1");
  }

  @Test
  void theListShowsTheNewestWorkflowsAndSaysWhenThereAreMore() throws Exception {
    HttpResponse<String> all = get("/workflows");

    assertThat(all.statusCode()).isEqualTo(200);
    assertThat(all.body())
        .contains("The newest 100 workflows that match. Narrow the filters to see others.");
    List<String> ids = ids(all.body());
    assertThat(ids).hasSize(100);
    assertThat(ids.subList(0, 5))
        .containsExactly("broken-1", Html.escape(EVIL_ID), "cancelled-1", "order-2", "order-1");
  }

  @Test
  void theListFiltersByIdTypeStateAndBlockedAndKeepsTheFiltersInTheForm() throws Exception {
    assertThat(ids(get("/workflows?id=order-").body())).containsExactly("order-2", "order-1");
    assertThat(ids(get("/workflows?id=bulk-00").body())).hasSize(10);
    assertThat(ids(get("/workflows?state=completed").body()))
        .containsExactly("order-2", "order-1", SLASHED_ID, "ship-1");
    assertThat(ids(get("/workflows?state=CANCELLED").body())).containsExactly("cancelled-1");
    assertThat(ids(get("/workflows?blocked=yes").body())).containsExactly("broken-1");
    assertThat(ids(get("/workflows?type=Echo").body()))
        .containsExactly("order-2", "order-1", SLASHED_ID);
    assertThat(ids(get("/workflows?type=Waiting&blocked=no&id=c").body()))
        .containsExactly("cancelled-1");

    String filtered = get("/workflows?id=order-&state=COMPLETED&blocked=no").body();
    assertThat(filtered)
        .contains("name=\"id\" value=\"order-\"")
        .contains("<option value=\"COMPLETED\" selected>Completed</option>")
        .contains("<option value=\"no\" selected>No</option>")
        .contains("2 workflows, newest first.");
    assertThat(get("/workflows?id=nothing-like-this").body()).contains("No workflow matches.");
    assertThat(get("/workflows?id=order-1").body()).contains("1 workflow, newest first.");
  }

  @Test
  void textFromWorkflowsAndFromTheAddressIsEscaped() throws Exception {
    String list = get("/workflows").body();
    assertThat(list)
        .contains("&lt;b&gt;evil&lt;/b&gt;&amp;co")
        .contains("&lt;img src=x onerror=alert(1)&gt;")
        .doesNotContain(EVIL_ID, EVIL_STATUS);

    String echoed = get("/workflows?type=%22%3E%3Cscript%3Ealert(1)%3C/script%3E").body();
    assertThat(echoed)
        .contains("value=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\"")
        .doesNotContain("<script>");

    String badState = get("/workflows?state=%3Cscript%3E").body();
    assertThat(badState)
        .contains("There is no workflow state called &quot;&lt;script&gt;&quot;.")
        .doesNotContain("<script>");
  }

  @Test
  void everyPageForbidsScriptsFramesAndSniffing() throws Exception {
    for (String path : List.of("/", "/workflows", "/style.css", "/nothing-here")) {
      HttpResponse<String> response = get(path);
      assertThat(response.headers().firstValue("Content-Security-Policy"))
          .as(path)
          .contains(
              "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none';"
                  + " base-uri 'none'");
      assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    for (String path : List.of("/", "/workflows")) {
      assertThat(get(path).body())
          .doesNotContain("<script", "style=\"")
          .doesNotContainPattern("<[^>]*\\son[a-z]+=");
    }
  }

  @Test
  void badFiltersUnknownPagesAndChangesAreRefused() throws Exception {
    HttpResponse<String> badState = get("/workflows?state=sleeping");
    assertThat(badState.statusCode()).isEqualTo(400);
    assertThat(badState.body()).contains("There is no workflow state called &quot;sleeping&quot;.");
    assertThat(get("/workflows?blocked=maybe").statusCode()).isEqualTo(400);

    HttpResponse<String> post =
        http.send(
            HttpRequest.newBuilder(dashboard.uri().resolve("/"))
                .POST(HttpRequest.BodyPublishers.ofString("retry"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(405);
    assertThat(post.headers().firstValue("Allow")).contains("GET, HEAD");

    HttpResponse<String> head =
        http.send(
            HttpRequest.newBuilder(dashboard.uri().resolve("/workflows"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(head.statusCode()).isEqualTo(200);
    assertThat(head.body()).isEmpty();

    HttpResponse<String> css = get("/style.css");
    assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
    assertThat(css.body()).contains(".badge.blocked");
  }

  @Test
  void aDatabaseFailureShowsAPlainErrorWithoutItsDetails() throws Exception {
    PGSimpleDataSource wrongPassword = new PGSimpleDataSource();
    wrongPassword.setUrl(postgres.getJdbcUrl());
    wrongPassword.setUser(postgres.getUsername());
    wrongPassword.setPassword("not-the-password");
    try (DurastepDashboard broken =
        DurastepDashboard.start(DurastepClient.connect(wrongPassword), 0)) {
      HttpResponse<String> response = get(broken.uri(), "/");

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body())
          .contains("The dashboard could not read the database. The details are in its log.")
          .doesNotContain("password", "PSQL", "Exception", postgres.getUsername());
    }
  }

  @Test
  void aDashboardListensOnlyOnThisMachineAndClosesItsPort() throws Exception {
    URI address;
    try (DurastepDashboard local = DurastepDashboard.start(engine.client(), 0)) {
      address = local.uri();
      assertThat(InetAddress.getByName(address.getHost()).isLoopbackAddress()).isTrue();
      assertThat(address.getPath()).isEqualTo("/");
      assertThat(get(address, "/").statusCode()).isEqualTo(200);
    }
    assertThatThrownBy(() -> get(address, "/")).isInstanceOf(IOException.class);
  }

  @Test
  void anIpv6LoopbackAddressIsShownInBracketsAndAnUnknownOneIsRefused() throws Exception {
    InetSocketAddress ipv6 = new InetSocketAddress(InetAddress.getByAddress(IPV6_LOOPBACK), 0);
    try (DurastepDashboard local = DurastepDashboard.start(engine.client(), ipv6)) {
      assertThat(local.uri().toString()).startsWith("http://[0:0:0:0:0:0:0:1]:");
      assertThat(get(local.uri(), "/").statusCode()).isEqualTo(200);
    }
    assertThatThrownBy(
            () ->
                DurastepDashboard.start(
                    engine.client(), InetSocketAddress.createUnresolved("no-such-host", 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-such-host");
  }

  @Test
  void aPortInUseIsReportedClearly() {
    InetSocketAddress taken =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), dashboard.uri().getPort());
    assertThatThrownBy(() -> DurastepDashboard.start(engine.client(), taken))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("could not open the dashboard on");
  }

  @Test
  void aWorkflowPageShowsItsSummaryStepsAndHistory() throws Exception {
    HttpResponse<String> page = get("/workflows/ship-1");

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body())
        .contains("<title>ship-1 · Durastep</title>")
        .contains("<tr><th>Type</th><td>Shipping</td></tr>")
        .contains("<span class=\"badge completed\">Completed</span>")
        .contains("<tr><th>Duration</th><td>took ")
        .contains("<svg class=\"gantt\"")
        .contains("<rect class=\"bar completed\"")
        .contains(">Shop.pack</text>", ">sleeping for PT0.2S</text>")
        .contains(">waiting for signal go up to PT1M</text>")
        .contains("<title>Shop.pack: ")
        .contains("received signal go", "woke up from #", "completed</td>")
        .doesNotContain("order-9", "box for", "and go", "Why it is blocked")
        .doesNotContain("http-equiv=\"refresh\"");
  }

  @Test
  void aBlockedWorkflowsPageSaysWhyAndHowToRetryIt() throws Exception {
    String page = get("/workflows/broken-1").body();

    assertThat(page)
        .contains("<h2>Why it is blocked</h2>")
        .contains("a bug in version 1.4.1")
        .contains("<code>durastep retry-now broken-1 --reason &quot;…&quot;</code>")
        .contains("<span class=\"badge blocked\">Blocked</span>")
        .contains("running for ")
        .contains("No steps yet.")
        .contains("<meta http-equiv=\"refresh\" content=\"5\">");
  }

  @Test
  void theListLinksToEachWorkflowEvenWhenItsIdNeedsEncoding() throws Exception {
    String list = get("/workflows").body();

    String slashed = hrefOf(list, Html.escape(SLASHED_ID));
    assertThat(slashed).isEqualTo("/workflows/team%2Fa%20b%2Bc");
    assertThat(get(slashed).body()).contains("<h1>team/a b+c</h1>");

    String evil = hrefOf(list, Html.escape(EVIL_ID));
    assertThat(evil).isEqualTo("/workflows/%3Cb%3Eevil%3C%2Fb%3E%26co");
    String evilPage = get(evil).body();
    assertThat(evilPage)
        .contains("<h1>&lt;b&gt;evil&lt;/b&gt;&amp;co</h1>")
        .contains("&lt;img src=x onerror=alert(1)&gt;")
        .doesNotContain(EVIL_ID, EVIL_STATUS);
  }

  @Test
  void anUnknownWorkflowOrAMalformedAddressIsNotFound() throws Exception {
    HttpResponse<String> unknown = get("/workflows/no-such-workflow");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.body())
        .contains("There is no workflow with ID &quot;no-such-workflow&quot;.");

    HttpResponse<String> script = get("/workflows/%3Cscript%3E");
    assertThat(script.statusCode()).isEqualTo(404);
    assertThat(script.body()).contains("&lt;script&gt;").doesNotContain("<script>");

    assertThat(get("/workflows/").statusCode()).isEqualTo(404);
    assertThat(get("/workflows/order-1/history").statusCode()).isEqualTo(404);
  }

  private static String card(String alarm, String href, long count, String label) {
    return "<a class=\"card"
        + alarm
        + "\" href=\""
        + href
        + "\"><span class=\"count\">"
        + count
        + "</span><span class=\"label\">"
        + label
        + "</span>";
  }

  private static List<String> ids(String html) {
    Matcher matcher = ID_CELL.matcher(html);
    return matcher.results().map(result -> result.group(2)).toList();
  }

  private static String hrefOf(String html, String escapedId) {
    return ID_CELL
        .matcher(html)
        .results()
        .filter(result -> result.group(2).equals(escapedId))
        .map(result -> result.group(1))
        .findFirst()
        .orElseThrow();
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return get(dashboard.uri(), path);
  }

  private static HttpResponse<String> get(URI base, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(base.resolve(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
