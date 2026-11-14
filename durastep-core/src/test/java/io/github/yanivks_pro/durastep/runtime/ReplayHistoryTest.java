package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowCompleted;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowFailed;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.WorkflowStarted;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayHistoryTest {

  private static final Payload NOTHING = new Payload("null");

  @Test
  void rejectsAGapInTheSequenceNumbers() {
    List<HistoryEntry> entries = new ArrayList<>(History.startedWith("x").entries());
    entries.add(new HistoryEntry(3, History.START, new ActivityCompleted(2, NOTHING)));

    assertThatThrownBy(() -> ReplayHistory.of(entries))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("without gaps");
  }

  @Test
  void rejectsAResultForAnActivityThatWasNeverScheduled() {
    History history = History.startedWith("x").completed(7, "result");

    assertThatThrownBy(() -> ReplayHistory.of(history.entries()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never scheduled");
  }

  @Test
  void rejectsTwoResultsForTheSameActivity() {
    History history =
        History.startedWith("x").scheduled("A.a").completed(2, "one").completed(2, "two");

    assertThatThrownBy(() -> ReplayHistory.of(history.entries()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than one outcome");
  }

  @Test
  void rejectsASecondWorkflowStarted() {
    History history = History.startedWith("x").append(History.START, new WorkflowStarted(NOTHING));

    assertThatThrownBy(() -> ReplayHistory.of(history.entries()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("first event");
  }

  @Test
  void refusesToReplayAWorkflowThatHasAlreadyFinished() {
    History completed =
        History.startedWith("x").append(History.START, new WorkflowCompleted(NOTHING));
    History failed =
        History.startedWith("x")
            .append(History.START, new WorkflowFailed(new Failure("E", "boom")));

    assertThatThrownBy(() -> ReplayHistory.of(completed.entries()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ReplayHistory.of(failed.entries()))
        .isInstanceOf(IllegalStateException.class);
  }
}
