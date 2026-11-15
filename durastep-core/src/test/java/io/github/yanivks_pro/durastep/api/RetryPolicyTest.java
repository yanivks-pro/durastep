package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  static class PaymentException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  static final class CardDeclinedException extends PaymentException {
    private static final long serialVersionUID = 1L;
  }

  interface Permanent {}

  static final class AccountClosedException extends RuntimeException implements Permanent {
    private static final long serialVersionUID = 1L;
  }

  @Test
  void theDefaultsMatchTheRequirements() {
    assertThat(RetryPolicy.DEFAULT.maxAttempts()).isEqualTo(5);
    assertThat(RetryPolicy.DEFAULT.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
    assertThat(RetryPolicy.DEFAULT.maxBackoff()).isEqualTo(Duration.ofMinutes(1));
    assertThat(RetryPolicy.DEFAULT.nonRetryableTypes()).isEmpty();
    assertThat(RetryPolicy.none().maxAttempts()).isEqualTo(1);
  }

  @Test
  void aNonRetryableTypeAlsoCoversItsSubclassesAndInterfaces() {
    RetryPolicy policy = RetryPolicy.builder().nonRetryable(PaymentException.class).build();

    assertThat(policy.isRetryable(new PaymentException())).isFalse();
    assertThat(policy.isRetryable(new CardDeclinedException())).isFalse();
    assertThat(policy.isRetryable(new UncheckedIOException(new IOException("timeout")))).isTrue();
  }

  @Test
  void anInterfaceCanBeNonRetryable() {
    RetryPolicy policy =
        new RetryPolicy(
            5, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(Permanent.class.getName()));

    assertThat(policy.isRetryable(new AccountClosedException())).isFalse();
    assertThat(policy.isRetryable(new IllegalStateException())).isTrue();
  }

  @Test
  void invalidSettingsAreRejected() {
    assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RetryPolicy.builder()
                    .initialBackoff(Duration.ofSeconds(10))
                    .maxBackoff(Duration.ofSeconds(5))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shorter than initialBackoff");
  }

  @Test
  void theListOfNonRetryableTypesCannotBeChangedAfterwards() {
    RetryPolicy policy = RetryPolicy.builder().nonRetryable(PaymentException.class).build();

    assertThatThrownBy(() -> policy.nonRetryableTypes().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
