package io.github.yanivks_pro.durastep.dashboard;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.EngineStats;
import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowQuery;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OverviewPage {

  static final int BLOCKED_SHOWN = 10;

  private OverviewPage() {}

  static String render(DurastepClient client) {
    Map<WorkflowState, Long> counts = client.countByState();
    EngineStats stats = client.stats();
    List<WorkflowDescription> blocked =
        client.list(WorkflowQuery.all().blocked(true).limit(BLOCKED_SHOWN));

    StringBuilder body = new StringBuilder("<section class=\"cards\">\n");
    body.append(card("/workflows?state=RUNNING", counts.get(WorkflowState.RUNNING), "Running", ""));
    body.append(card("/workflows?blocked=yes", stats.blocked(), "Blocked", "warn"));
    body.append(
        card("/workflows?state=COMPLETED", counts.get(WorkflowState.COMPLETED), "Completed", ""));
    body.append(card("/workflows?state=FAILED", counts.get(WorkflowState.FAILED), "Failed", "bad"));
    body.append(
        card("/workflows?state=CANCELLED", counts.get(WorkflowState.CANCELLED), "Cancelled", ""));
    body.append("</section>\n");

    List<String> perQueue = new ArrayList<>();
    stats.readyActivities().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> perQueue.add(Html.escape(entry.getKey()) + ": " + entry.getValue()));
    body.append("<h2>Waiting for an engine</h2>\n<table class=\"facts\">\n")
        .append("<tr><th>Decisions</th><td>")
        .append(stats.readyDecisions())
        .append("</td></tr>\n<tr><th>Activities</th><td>")
        .append(perQueue.isEmpty() ? "0" : String.join(", ", perQueue))
        .append("</td></tr>\n<tr><th>Oldest waiting</th><td>")
        .append(Html.duration(stats.oldestReadyWork()))
        .append("</td></tr>\n</table>\n");

    body.append("<h2>Blocked workflows</h2>\n");
    if (blocked.isEmpty()) {
      body.append("<p class=\"empty\">No workflow is blocked.</p>\n");
    } else {
      body.append(Html.workflowTable(blocked));
      if (blocked.size() == BLOCKED_SHOWN) {
        body.append("<p class=\"more\">The newest ")
            .append(BLOCKED_SHOWN)
            .append(". <a href=\"/workflows?blocked=yes\">See all blocked workflows</a></p>\n");
      }
    }
    return Html.page("Overview", Html.Section.OVERVIEW, true, body.toString());
  }

  static String card(String href, long count, String label, String alarm) {
    return "<a class=\"card"
        + (count > 0 && !alarm.isEmpty() ? " " + alarm : "")
        + "\" href=\""
        + href
        + "\"><span class=\"count\">"
        + count
        + "</span><span class=\"label\">"
        + label
        + "</span></a>\n";
  }
}
