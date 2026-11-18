package io.github.yanivks_pro.durastep.dashboard;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class Parameters {

  private static final Pattern AMPERSAND = Pattern.compile("&");

  private final Map<String, String> values;

  private Parameters(Map<String, String> values) {
    this.values = values;
  }

  static Parameters parse(String rawQuery) {
    Map<String, String> values = new HashMap<>();
    if (rawQuery != null) {
      for (String pair : AMPERSAND.splitAsStream(rawQuery).toList()) {
        int equals = pair.indexOf('=');
        String name = decode(equals < 0 ? pair : pair.substring(0, equals));
        String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
        values.putIfAbsent(name, value.strip());
      }
    }
    return new Parameters(values);
  }

  Optional<String> get(String name) {
    return Optional.ofNullable(values.get(name)).filter(value -> !value.isEmpty());
  }

  private static String decode(String text) {
    try {
      return URLDecoder.decode(text, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("the address is not encoded correctly");
    }
  }
}
