package io.github.yanivks_pro.durastep.dashboard;

import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class Html {

  enum Section {
    OVERVIEW,
    WORKFLOWS,
    NONE
  }

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private Html() {}

  static String escape(String text) {
    StringBuilder escaped = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  static String time(Instant instant) {
    return instant == null ? "" : TIME.format(instant);
  }

  static String duration(Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    if (seconds < 60) {
      return seconds + " s";
    }
    if (seconds < 3_600) {
      return seconds / 60 + " min " + seconds % 60 + " s";
    }
    if (seconds < 86_400) {
      return seconds / 3_600 + " h " + seconds % 3_600 / 60 + " min";
    }
    return seconds / 86_400 + " d " + seconds % 86_400 / 3_600 + " h";
  }

  static String label(WorkflowState state) {
    String name = state.name().toLowerCase(Locale.ROOT);
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  static String workflowTable(List<WorkflowDescription> workflows) {
    StringBuilder html =
        new StringBuilder(
            """
            <div class="scroll"><table class="workflows">
            <thead><tr><th>ID</th><th>Type</th><th>State</th><th>Status</th>\
            <th>Started</th><th>Finished</th></tr></thead>
            <tbody>
            """);
    for (WorkflowDescription workflow : workflows) {
      html.append("<tr><td class=\"id\">")
          .append(escape(workflow.id()))
          .append("</td><td>")
          .append(escape(workflow.type()))
          .append("</td><td>")
          .append(stateBadge(workflow))
          .append("</td><td>")
          .append(escape(workflow.status() == null ? "" : workflow.status()))
          .append("</td><td>")
          .append(time(workflow.startedAt()))
          .append("</td><td>")
          .append(time(workflow.closedAt()))
          .append("</td></tr>\n");
    }
    return html.append("</tbody>\n</table></div>\n").toString();
  }

  private static String stateBadge(WorkflowDescription workflow) {
    String state = workflow.state().name().toLowerCase(Locale.ROOT);
    String badge = "<span class=\"badge " + state + "\">" + label(workflow.state()) + "</span>";
    return workflow.blocked() ? badge + " <span class=\"badge blocked\">Blocked</span>" : badge;
  }

  static String page(String title, Section section, boolean refresh, String body) {
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        %s<title>%s · Durastep</title>
        <link rel="stylesheet" href="/style.css">
        </head>
        <body>
        <header>
        <a class="brand" href="/">Durastep</a>
        <nav>%s%s</nav>
        <span class="read-only">Read-only</span>
        </header>
        <main>
        <h1>%s</h1>
        %s</main>
        <footer>This dashboard only shows workflows. To change one, use the CLI: \
        <code>durastep --help</code>.</footer>
        </body>
        </html>
        """
        .formatted(
            refresh ? "<meta http-equiv=\"refresh\" content=\"5\">\n" : "",
            escape(title),
            link("/", "Overview", section == Section.OVERVIEW),
            link("/workflows", "Workflows", section == Section.WORKFLOWS),
            escape(title),
            body);
  }

  private static String link(String href, String text, boolean current) {
    return "<a href=\""
        + href
        + "\""
        + (current ? " aria-current=\"page\"" : "")
        + ">"
        + text
        + "</a>";
  }
}
