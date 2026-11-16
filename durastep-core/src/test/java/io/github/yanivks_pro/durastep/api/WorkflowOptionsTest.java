package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkflowOptionsTest {

  @Test
  void byDefaultAWorkflowHasNoDeadline() {
    assertThat(WorkflowOptions.DEFAULT.timeout()).isEmpty();
  }

  @Test
  void aTimeoutCanBeSetAndCompared() {
    assertThat(WorkflowOptions.timeout(Duration.ofHours(1)).timeout())
        .contains(Duration.ofHours(1));
    assertThat(WorkflowOptions.timeout(Duration.ofHours(1)))
        .isEqualTo(WorkflowOptions.timeout(Duration.ofHours(1)))
        .hasSameHashCodeAs(WorkflowOptions.timeout(Duration.ofHours(1)))
        .isNotEqualTo(WorkflowOptions.DEFAULT);
  }

  @Test
  void theTimeoutMustBePositive() {
    assertThatThrownBy(() -> WorkflowOptions.timeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
