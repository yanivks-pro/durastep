package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkflowStateTest {

  @Test
  void onlyRunningIsNotFinal() {
    assertThat(WorkflowState.RUNNING.isFinal()).isFalse();
    assertThat(WorkflowState.COMPLETED.isFinal()).isTrue();
    assertThat(WorkflowState.FAILED.isFinal()).isTrue();
    assertThat(WorkflowState.CANCELLED.isFinal()).isTrue();
  }
}
