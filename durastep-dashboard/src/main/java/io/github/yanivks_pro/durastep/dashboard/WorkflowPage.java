package io.github.yanivks_pro.durastep.dashboard;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.HistoryRecord;
import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.history.Timeline;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class WorkflowPage {

  static final String PREFIX = "/workflows/";
  static final int STEPS_SHOWN = 200;
  static final int EVENTS_SHOWN = 1_000;

  private static final int WIDTH = 1_000;
  private static final int LABEL_WIDTH = 300;
  private static final int RIGHT_MARGIN = 20;
  private static final int AXIS_HEIGHT = 24;
  private static final int ROW_HEIGHT = 26;
  private static final int LABEL_LENGTH = 40;

  private WorkflowPage() {}

  static String idFromPath(String rawPath) {
    String encoded = rawPath.substring(PREFIX.length());
    if (encoded.isEmpty() || encoded.contains("/")) {
      throw new NotFoundException("There is no page at this address.");
    }
    try {
      return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("the address is not encoded correctly");
    }
  }

  static String render(DurastepClient client, String id, Instant now) {
    WorkflowHandle<Object> handle;
    WorkflowDescription workflow;
    try {
      handle = client.handle(id);
      workflow = handle.describe();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new NotFoundException("There is no workflow with ID \"" + id + "\".");
    }
    Optional<String> error = handle.blockedError();
    List<HistoryRecord> history = handle.history();
    Timeline timeline = Timeline.of(history);
    Instant start = history.isEmpty() ? workflow.startedAt() : history.getFirst().recordedAt();
    Instant end = workflow.closedAt() != null ? workflow.closedAt() : now;
    if (!history.isEmpty() && history.getLast().recordedAt().isAfter(end)) {
      end = history.getLast().recordedAt();
    }

    StringBuilder body = new StringBuilder();
    body.append(summary(workflow, now));
    error.ifPresent(message -> body.append(problem(workflow.id(), message)));
    body.append("<h2>Steps</h2>\n");
    if (timeline.steps().isEmpty()) {
      body.append("<p class=\"empty\">No steps yet.</p>\n");
    } else {
      body.append(chart(timeline.steps(), start, end));
    }
    body.append("<h2>History</h2>\n").append(events(workflow.id(), timeline.entries(), start));
    return Html.page(
        workflow.id(),
        Html.Section.WORKFLOWS,
        workflow.state() == WorkflowState.RUNNING,
        body.toString());
  }

  private static String summary(WorkflowDescription workflow, Instant now) {
    String took =
        workflow.closedAt() != null
            ? "took " + Html.duration(Duration.between(workflow.startedAt(), workflow.closedAt()))
            : "running for " + Html.duration(Duration.between(workflow.startedAt(), now));
    return "<table class=\"facts\">\n"
        + row("Type", Html.escape(workflow.type()))
        + row("State", Html.stateBadge(workflow))
        + row("Status", Html.escape(workflow.status() == null ? "" : workflow.status()))
        + row("Started", Html.time(workflow.startedAt()))
        + row("Finished", Html.time(workflow.closedAt()))
        + row("Duration", took)
        + "</table>\n";
  }

  private static String row(String name, String value) {
    return "<tr><th>" + name + "</th><td>" + value + "</td></tr>\n";
  }

  private static String problem(String id, String error) {
    return "<section class=\"problem\">\n<h2>Why it is blocked</h2>\n<pre>"
        + Html.escape(error)
        + "</pre>\n<p>Durastep retries it with backoff. Once the cause is fixed, retry it now with"
        + " <code>durastep retry-now "
        + Html.escape(id)
        + " --reason &quot;…&quot;</code>.</p>\n</section>\n";
  }

  static String chart(List<Timeline.Step> steps, Instant start, Instant end) {
    List<Timeline.Step> shown = steps.subList(0, Math.min(steps.size(), STEPS_SHOWN));
    long span = Math.max(1, Duration.between(start, end).toMillis());
    int barsWidth = WIDTH - LABEL_WIDTH - RIGHT_MARGIN;
    int height = AXIS_HEIGHT + shown.size() * ROW_HEIGHT;
    StringBuilder svg =
        new StringBuilder("<div class=\"scroll\">\n<svg class=\"gantt\" viewBox=\"0 0 ")
            .append(WIDTH)
            .append(' ')
            .append(height)
            .append("\" role=\"img\" aria-label=\"The workflow's steps over time\">\n")
            .append("<text class=\"axis\" x=\"")
            .append(LABEL_WIDTH)
            .append("\" y=\"14\">0 s</text>\n")
            .append("<text class=\"axis\" x=\"")
            .append(WIDTH - RIGHT_MARGIN)
            .append("\" y=\"14\" text-anchor=\"end\">")
            .append(Html.duration(Duration.ofMillis(span)))
            .append("</text>\n");
    for (int i = 0; i < shown.size(); i++) {
      Timeline.Step step = shown.get(i);
      Instant stepEnd = step.endedAt() != null ? step.endedAt() : end;
      long from = Duration.between(start, step.startedAt()).toMillis();
      long length = Duration.between(step.startedAt(), stepEnd).toMillis();
      long x = LABEL_WIDTH + Math.round((double) from * barsWidth / span);
      long width = Math.max(2, Math.round((double) length * barsWidth / span));
      int y = AXIS_HEIGHT + i * ROW_HEIGHT;
      String outcome = step.outcome().name().toLowerCase(Locale.ROOT);
      svg.append("<line class=\"track\" x1=\"")
          .append(LABEL_WIDTH)
          .append("\" y1=\"")
          .append(y + 13)
          .append("\" x2=\"")
          .append(WIDTH - RIGHT_MARGIN)
          .append("\" y2=\"")
          .append(y + 13)
          .append("\"/>\n<text x=\"0\" y=\"")
          .append(y + 17)
          .append("\">")
          .append(Html.escape(shorten(step.name())))
          .append("</text>\n<rect class=\"bar ")
          .append(outcome)
          .append("\" x=\"")
          .append(x)
          .append("\" y=\"")
          .append(y + 5)
          .append("\" width=\"")
          .append(width)
          .append("\" height=\"16\" rx=\"3\"><title>")
          .append(Html.escape(step.name()))
          .append(": ")
          .append(Html.duration(Duration.ofMillis(length)))
          .append(", ")
          .append(outcome.replace('_', ' '))
          .append("</title></rect>\n");
    }
    svg.append("</svg>\n</div>\n")
        .append("<p class=\"legend\"><span class=\"swatch completed\"></span>Completed")
        .append(" <span class=\"swatch failed\"></span>Failed")
        .append(" <span class=\"swatch timed_out\"></span>Timed out")
        .append(" <span class=\"swatch running\"></span>Running")
        .append(" <span class=\"swatch stopped\"></span>Stopped when the workflow ended</p>\n");
    if (steps.size() > STEPS_SHOWN) {
      svg.append("<p class=\"more\">The first ")
          .append(STEPS_SHOWN)
          .append(" of ")
          .append(steps.size())
          .append(" steps.</p>\n");
    }
    return svg.toString();
  }

  static String events(String id, List<Timeline.Entry> entries, Instant start) {
    List<Timeline.Entry> shown = entries.subList(0, Math.min(entries.size(), EVENTS_SHOWN));
    StringBuilder html =
        new StringBuilder(
            "<div class=\"scroll\"><table class=\"history\">\n"
                + "<thead><tr><th>#</th><th>After</th><th>What happened</th><th>At</th></tr>"
                + "</thead>\n<tbody>\n");
    for (Timeline.Entry entry : shown) {
      html.append("<tr><td>")
          .append(entry.seq())
          .append("</td><td>+")
          .append(Html.duration(Duration.between(start, entry.at())))
          .append("</td><td class=\"wrap\">")
          .append(Html.escape(entry.text()))
          .append("</td><td>")
          .append(Html.time(entry.at()))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table></div>\n");
    if (entries.size() > EVENTS_SHOWN) {
      html.append("<p class=\"more\">The first ")
          .append(EVENTS_SHOWN)
          .append(" of ")
          .append(entries.size())
          .append(" events. See all with <code>durastep history ")
          .append(Html.escape(id))
          .append("</code>.</p>\n");
    }
    return html.toString();
  }

  private static String shorten(String name) {
    return name.length() <= LABEL_LENGTH ? name : name.substring(0, LABEL_LENGTH - 1) + "…";
  }
}
