package io.github.yanivks_pro.durastep.store;

import io.github.yanivks_pro.durastep.runtime.HistoryEntry;
import java.util.ArrayList;
import java.util.List;

public record DecisionInput(
    String workflowType, List<HistoryEntry> history, List<InboxEntry> inbox) {

  public DecisionInput {
    history = List.copyOf(history);
    inbox = List.copyOf(inbox);
  }

  public List<HistoryEntry> historyWithInbox() {
    List<HistoryEntry> combined = new ArrayList<>(history);
    for (InboxEntry entry : inbox) {
      combined.add(new HistoryEntry(combined.size() + 1, entry.receivedAt(), entry.event()));
    }
    return List.copyOf(combined);
  }
}
