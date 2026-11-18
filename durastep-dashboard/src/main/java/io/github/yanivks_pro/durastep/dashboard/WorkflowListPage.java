package io.github.yanivks_pro.durastep.dashboard;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import io.github.yanivks_pro.durastep.api.WorkflowDescription;
import io.github.yanivks_pro.durastep.api.WorkflowQuery;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class WorkflowListPage {

  static final int SHOWN = 100;

  private WorkflowListPage() {}

  static String render(DurastepClient client, Parameters parameters) {
    Optional<String> id = parameters.get("id");
    Optional<String> type = parameters.get("type");
    Optional<WorkflowState> state = parameters.get("state").map(WorkflowListPage::state);
    Optional<Boolean> blocked = parameters.get("blocked").map(WorkflowListPage::blocked);

    WorkflowQuery query = WorkflowQuery.all().limit(SHOWN);
    if (id.isPresent()) {
      query = query.idStartsWith(id.get());
    }
    if (type.isPresent()) {
      query = query.type(type.get());
    }
    if (state.isPresent()) {
      query = query.state(state.get());
    }
    if (blocked.isPresent()) {
      query = query.blocked(blocked.get());
    }
    List<WorkflowDescription> workflows = client.list(query);

    StringBuilder body = new StringBuilder();
    body.append("<form class=\"filters\" method=\"get\" action=\"/workflows\">\n")
        .append(textField("id", "ID starts with", id))
        .append(textField("type", "Type", type))
        .append("<label>State <select name=\"state\">")
        .append(option("", "Any", state.isEmpty()));
    for (WorkflowState each : WorkflowState.values()) {
      body.append(option(each.name(), Html.label(each), state.equals(Optional.of(each))));
    }
    body.append("</select></label>\n<label>Blocked <select name=\"blocked\">")
        .append(option("", "Any", blocked.isEmpty()))
        .append(option("yes", "Yes", blocked.equals(Optional.of(true))))
        .append(option("no", "No", blocked.equals(Optional.of(false))))
        .append("</select></label>\n")
        .append("<button type=\"submit\">Filter</button>\n")
        .append("<a class=\"clear\" href=\"/workflows\">Clear</a>\n</form>\n");

    if (workflows.isEmpty()) {
      body.append("<p class=\"empty\">No workflow matches.</p>\n");
    } else {
      body.append("<p class=\"summary\">")
          .append(
              workflows.size() == SHOWN
                  ? "The newest "
                      + SHOWN
                      + " workflows that match. Narrow the filters to see others."
                  : workflows.size()
                      + (workflows.size() == 1 ? " workflow" : " workflows")
                      + ", newest first.")
          .append("</p>\n")
          .append(Html.workflowTable(workflows));
    }
    return Html.page("Workflows", Html.Section.WORKFLOWS, false, body.toString());
  }

  private static WorkflowState state(String name) {
    try {
      return WorkflowState.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("There is no workflow state called \"" + name + "\".");
    }
  }

  private static boolean blocked(String value) {
    return switch (value) {
      case "yes" -> true;
      case "no" -> false;
      default -> throw new BadRequestException("Blocked must be \"yes\" or \"no\".");
    };
  }

  private static String textField(String name, String label, Optional<String> value) {
    return "<label>"
        + label
        + " <input type=\"search\" name=\""
        + name
        + "\" value=\""
        + Html.escape(value.orElse(""))
        + "\"></label>\n";
  }

  private static String option(String value, String text, boolean selected) {
    return "<option value=\""
        + value
        + "\""
        + (selected ? " selected" : "")
        + ">"
        + text
        + "</option>";
  }
}
