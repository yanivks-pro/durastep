package io.github.yanivks_pro.durastep.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.PayloadTooLargeException;
import org.junit.jupiter.api.Test;

class LimitedPayloadConverterTest {

  private final LimitedPayloadConverter converter =
      new LimitedPayloadConverter(new JsonPayloadConverter(), 100);

  @Test
  void dataWithinTheLimitIsConvertedAsUsual() {
    assertThat(converter.toPayload("hello").json()).isEqualTo("\"hello\"");
    assertThat(converter.toArguments(new Object[] {"a", 1}).json()).isEqualTo("[\"a\",1]");
  }

  @Test
  void dataOverTheLimitIsRejectedWithItsSizeAndATip() {
    assertThatThrownBy(() -> converter.toPayload("x".repeat(200)))
        .isInstanceOfSatisfying(
            PayloadTooLargeException.class,
            e -> {
              assertThat(e.size()).isEqualTo(202);
              assertThat(e.limit()).isEqualTo(100);
            })
        .hasMessageContaining("more than the limit")
        .hasMessageContaining("pass a reference");
    assertThatThrownBy(() -> converter.toArguments(new Object[] {"x".repeat(200)}))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  @Test
  void theSizeIsCountedInBytesNotCharacters() {
    String fiftyEuroSigns = "€".repeat(50);

    assertThatThrownBy(() -> converter.toPayload(fiftyEuroSigns))
        .isInstanceOfSatisfying(
            PayloadTooLargeException.class, e -> assertThat(e.size()).isEqualTo(152));
  }

  @Test
  void readingDataIsNotLimited() {
    Payload large = new Payload("\"" + "x".repeat(200) + "\"");

    assertThat(converter.fromPayload(large, String.class)).isEqualTo("x".repeat(200));
  }
}
