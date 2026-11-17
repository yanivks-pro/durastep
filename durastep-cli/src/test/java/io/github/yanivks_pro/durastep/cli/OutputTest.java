package io.github.yanivks_pro.durastep.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputTest {

  @Test
  void encryptedDataIsShownAsEncryptedEverywhereInAnEvent() {
    String event =
        "{\"activity\":\"Payments.charge\","
            + "\"arguments\":{\"enc\":\"v1\",\"key\":\"k2\",\"data\":\"Zm9v\"},"
            + "\"options\":{\"queue\":\"default\"}}";

    assertThat(Output.compact(event))
        .isEqualTo(
            "{\"activity\":\"Payments.charge\",\"arguments\":\"[encrypted]\","
                + "\"options\":{\"queue\":\"default\"}}");
    assertThat(Output.compact("{\"enc\":\"v1\",\"key\":\"k2\",\"data\":\"Zm9v\"}"))
        .isEqualTo("\"[encrypted]\"");
    assertThat(Output.compact("[1,\"two\"]")).isEqualTo("[1,\"two\"]");
  }
}
