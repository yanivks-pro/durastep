package io.github.yanivks_pro.durastep.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ParametersTest {

  @Test
  void valuesAreDecodedTrimmedAndTheFirstOneWins() {
    Parameters parameters = Parameters.parse("id=order%2D4+2&type=%20Order%20&id=other&flag");

    assertThat(parameters.get("id")).contains("order-4 2");
    assertThat(parameters.get("type")).contains("Order");
    assertThat(parameters.get("flag")).isEmpty();
    assertThat(parameters.get("missing")).isEmpty();
  }

  @Test
  void anEmptyOrMissingQueryHasNoValues() {
    assertThat(Parameters.parse(null).get("id")).isEmpty();
    assertThat(Parameters.parse("").get("id")).isEmpty();
    assertThat(Parameters.parse("id=").get("id")).isEmpty();
  }

  @Test
  void aBrokenEncodingIsABadRequest() {
    assertThatThrownBy(() -> Parameters.parse("id=%zz"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("the address is not encoded correctly");
  }
}
