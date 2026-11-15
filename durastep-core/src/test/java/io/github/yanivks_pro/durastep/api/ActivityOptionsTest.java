package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActivityOptionsTest {

  @Test
  void theDefaultsMatchTheRequirements() {
    assertThat(ActivityOptions.DEFAULT.attemptTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(ActivityOptions.DEFAULT.totalTimeout()).isEmpty();
    assertThat(ActivityOptions.DEFAULT.retryPolicy()).isEqualTo(RetryPolicy.DEFAULT);
  }

  @Test
  void everySettingCanBeChanged() {
    RetryPolicy threeTries = RetryPolicy.builder().maxAttempts(3).build();

    ActivityOptions options =
        ActivityOptions.builder()
            .attemptTimeout(Duration.ofSeconds(10))
            .totalTimeout(Duration.ofMinutes(5))
            .retryPolicy(threeTries)
            .build();

    assertThat(options.attemptTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(options.totalTimeout()).contains(Duration.ofMinutes(5));
    assertThat(options.retryPolicy()).isEqualTo(threeTries);
    assertThat(options).isNotEqualTo(ActivityOptions.DEFAULT);
    assertThat(options)
        .isEqualTo(
            ActivityOptions.builder()
                .attemptTimeout(Duration.ofSeconds(10))
                .totalTimeout(Duration.ofMinutes(5))
                .retryPolicy(threeTries)
                .build())
        .hasSameHashCodeAs(
            ActivityOptions.builder()
                .attemptTimeout(Duration.ofSeconds(10))
                .totalTimeout(Duration.ofMinutes(5))
                .retryPolicy(threeTries)
                .build());
  }

  @Test
  void timeoutsMustBePositive() {
    assertThatThrownBy(() -> ActivityOptions.builder().attemptTimeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActivityOptions.builder().totalTimeout(Duration.ofSeconds(-1)).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
