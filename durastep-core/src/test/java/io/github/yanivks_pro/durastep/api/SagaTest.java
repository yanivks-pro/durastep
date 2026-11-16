package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SagaTest {

  private final Saga saga = new Saga();
  private final List<String> undone = new ArrayList<>();

  @Test
  void undoStepsRunInReverseOrder() {
    saga.addCompensation(() -> undone.add("flight"));
    saga.addCompensation(() -> undone.add("hotel"));
    saga.addCompensation(() -> undone.add("car"));

    saga.compensate();

    assertThat(undone).containsExactly("car", "hotel", "flight");
  }

  @Test
  void aFailingUndoStepDoesNotStopTheOthersAndTheFirstFailureIsThrownAtTheEnd() {
    saga.addCompensation(() -> undone.add("flight"));
    saga.addCompensation(
        () -> {
          throw new IllegalStateException("hotel refused");
        });
    saga.addCompensation(
        () -> {
          throw new IllegalArgumentException("car refused");
        });

    assertThatThrownBy(saga::compensate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("car refused");
    assertThat(undone).containsExactly("flight");
  }

  @Test
  void compensatingTwiceRunsEachUndoStepOnce() {
    saga.addCompensation(() -> undone.add("flight"));

    saga.compensate();
    saga.compensate();

    assertThat(undone).containsExactly("flight");
  }

  static final class EngineSignal extends Error {
    private static final long serialVersionUID = 1L;
  }

  @Test
  void anErrorFromTheEngineIsNeverSwallowed() {
    saga.addCompensation(() -> undone.add("flight"));
    saga.addCompensation(
        () -> {
          throw new EngineSignal();
        });

    assertThatThrownBy(saga::compensate).isInstanceOf(EngineSignal.class);
    assertThat(undone).isEmpty();
  }
}
