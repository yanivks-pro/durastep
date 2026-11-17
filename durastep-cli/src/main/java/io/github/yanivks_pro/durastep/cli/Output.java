package io.github.yanivks_pro.durastep.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class Output {

  static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private static final JsonMapper COMPACT = JsonMapper.builder().build();

  private static final DateTimeFormatter LOCAL_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
          .withZone(ZoneId.systemDefault());

  private Output() {}

  static ObjectNode object() {
    return JSON.createObjectNode();
  }

  static ArrayNode array() {
    return JSON.createArrayNode();
  }

  static JsonNode parse(String json) {
    return JSON.readTree(json);
  }

  static String compact(String json) {
    return COMPACT.writeValueAsString(withoutEncryptedData(parse(json)));
  }

  static JsonNode withoutEncryptedData(JsonNode node) {
    if (node.isObject() && node.has("enc") && node.has("key") && node.has("data")) {
      return JSON.getNodeFactory().stringNode("[encrypted]");
    }
    if (node instanceof ObjectNode object) {
      ObjectNode copy = JSON.createObjectNode();
      object
          .properties()
          .forEach(field -> copy.set(field.getKey(), withoutEncryptedData(field.getValue())));
      return copy;
    }
    if (node instanceof ArrayNode array) {
      ArrayNode copy = JSON.createArrayNode();
      array.forEach(element -> copy.add(withoutEncryptedData(element)));
      return copy;
    }
    return node;
  }

  static void print(Io io, JsonNode json) {
    io.out().println(JSON.writeValueAsString(json));
  }

  static String time(Instant instant) {
    return instant == null ? "" : LOCAL_TIME.format(instant);
  }

  static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  static String seconds(Duration duration) {
    return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1_000.0);
  }

  static void table(Io io, List<String> header, List<List<String>> rows) {
    int[] widths = new int[header.size()];
    List<List<String>> all = new ArrayList<>();
    all.add(header);
    all.addAll(rows);
    for (List<String> row : all) {
      for (int i = 0; i < row.size(); i++) {
        widths[i] = Math.max(widths[i], row.get(i).length());
      }
    }
    for (List<String> row : all) {
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < row.size(); i++) {
        line.append(i == row.size() - 1 ? row.get(i) : pad(row.get(i), widths[i] + 2));
      }
      io.out().println(line.toString().stripTrailing());
    }
  }

  private static String pad(String text, int width) {
    return text + " ".repeat(Math.max(0, width - text.length()));
  }
}
